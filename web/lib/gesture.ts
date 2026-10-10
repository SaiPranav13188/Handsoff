// Grab / release detection from MediaPipe Hands landmarks.
//
// "Curl score": the average distance from each fingertip to the palm centre,
// normalised by hand size (wrist → middle-finger MCP). With real hand
// proportions an open hand gives ~0.9–1.2 (spread fingers a bit more) and a
// fist ~0.3–0.5. The raw ratio is
// smoothed with an EMA, classified with hysteresis, and a state is only
// accepted after it holds for a minimum streak of consecutive frames.

export type Landmark = { x: number; y: number; z?: number };
export type HandState = "none" | "open" | "closed";
export type GestureEvent = "grab" | "release" | null;

export type GestureFrame = {
  state: HandState;
  event: GestureEvent;
  /** Smoothed openness ratio (higher = more open). NaN when no hand. */
  openness: number;
  /** Palm centre in normalised video coords (0..1), un-mirrored. */
  palm: { x: number; y: number } | null;
};

export type GestureOptions = {
  /** EMA weight of the newest sample (0..1). */
  alpha: number;
  /** Below this smoothed ratio the hand counts as closed. */
  closedBelow: number;
  /** Above this smoothed ratio the hand counts as open. */
  openAbove: number;
  /** Consecutive frames a new state must hold before it is accepted. */
  minStreak: number;
  /** Frames without a hand before the state drops to "none". */
  lostAfter: number;
};

export const DEFAULT_GESTURE_OPTIONS: GestureOptions = {
  alpha: 0.35,
  closedBelow: 0.6,
  openAbove: 0.82,
  minStreak: 4,
  lostAfter: 8,
};

const WRIST = 0;
const PALM_POINTS = [0, 5, 9, 13, 17];
const FINGERTIPS = [8, 12, 16, 20]; // thumb tip left out: it barely moves in a fist
const MIDDLE_MCP = 9;

/**
 * Raw openness ratio for one hand. `aspect` is video width / height, so the
 * normalised x/y coordinates are compared in the same (pixel-proportional) units.
 */
export function opennessRatio(lm: Landmark[], aspect = 1): number {
  const px = (p: Landmark) => ({ x: p.x * aspect, y: p.y });
  const palm = PALM_POINTS.map((i) => px(lm[i])).reduce(
    (acc, p) => ({ x: acc.x + p.x / PALM_POINTS.length, y: acc.y + p.y / PALM_POINTS.length }),
    { x: 0, y: 0 },
  );
  const size = dist(px(lm[WRIST]), px(lm[MIDDLE_MCP]));
  if (size < 1e-6) return NaN;
  const reach = FINGERTIPS.reduce((acc, i) => acc + dist(px(lm[i]), palm), 0) / FINGERTIPS.length;
  return reach / size;
}

export function palmCenter(lm: Landmark[]): { x: number; y: number } {
  let x = 0;
  let y = 0;
  for (const i of PALM_POINTS) {
    x += lm[i].x;
    y += lm[i].y;
  }
  return { x: x / PALM_POINTS.length, y: y / PALM_POINTS.length };
}

function dist(a: { x: number; y: number }, b: { x: number; y: number }) {
  return Math.hypot(a.x - b.x, a.y - b.y);
}

export class GestureTracker {
  private opts: GestureOptions;
  private ema = NaN;
  private state: HandState = "none";
  private candidate: HandState = "none";
  private streak = 0;
  private missing = 0;

  constructor(opts: Partial<GestureOptions> = {}) {
    this.opts = { ...DEFAULT_GESTURE_OPTIONS, ...opts };
  }

  get current(): HandState {
    return this.state;
  }

  reset() {
    this.ema = NaN;
    this.state = "none";
    this.candidate = "none";
    this.streak = 0;
    this.missing = 0;
  }

  /** Feed one video frame's landmarks (or null when no hand was detected). */
  update(landmarks: Landmark[] | null, aspect = 1): GestureFrame {
    if (!landmarks) {
      this.missing++;
      if (this.missing >= this.opts.lostAfter && this.state !== "none") {
        this.state = "none";
        this.candidate = "none";
        this.streak = 0;
        this.ema = NaN;
      }
      return { state: this.state, event: null, openness: NaN, palm: null };
    }
    this.missing = 0;

    const raw = opennessRatio(landmarks, aspect);
    if (Number.isFinite(raw)) {
      this.ema = Number.isFinite(this.ema) ? this.opts.alpha * raw + (1 - this.opts.alpha) * this.ema : raw;
    }

    // Hysteresis: between the two thresholds the previous reading wins.
    let reading: HandState = this.candidate === "none" ? this.state : this.candidate;
    if (this.ema < this.opts.closedBelow) reading = "closed";
    else if (this.ema > this.opts.openAbove) reading = "open";
    else if (reading === "none") reading = this.ema >= (this.opts.closedBelow + this.opts.openAbove) / 2 ? "open" : "closed";

    if (reading === this.candidate) this.streak++;
    else {
      this.candidate = reading;
      this.streak = 1;
    }

    let event: GestureEvent = null;
    if (this.candidate !== this.state && this.streak >= this.opts.minStreak) {
      const prev = this.state;
      this.state = this.candidate;
      if (prev === "open" && this.state === "closed") event = "grab";
      else if (prev === "closed" && this.state === "open") event = "release";
    }

    return { state: this.state, event, openness: this.ema, palm: palmCenter(landmarks) };
  }
}
