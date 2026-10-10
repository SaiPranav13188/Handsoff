import type { Landmark } from "./gesture";

const CONNECTIONS: [number, number][] = [
  [0, 1], [1, 2], [2, 3], [3, 4],
  [0, 5], [5, 6], [6, 7], [7, 8],
  [5, 9], [9, 10], [10, 11], [11, 12],
  [9, 13], [13, 14], [14, 15], [15, 16],
  [13, 17], [17, 18], [18, 19], [19, 20],
  [0, 17],
];

/** Draws the hand skeleton onto a canvas sized to the (mirrored) preview. */
export function drawHand(canvas: HTMLCanvasElement, lm: Landmark[] | null, color: string) {
  const ctx = canvas.getContext("2d");
  if (!ctx) return;
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth;
  const h = canvas.clientHeight;
  if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
  }
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);
  if (!lm) return;

  const pt = (p: Landmark) => [(1 - p.x) * w, p.y * h] as const; // mirrored like the preview
  ctx.lineWidth = 2;
  ctx.lineCap = "round";
  ctx.strokeStyle = color;
  ctx.globalAlpha = 0.85;
  ctx.beginPath();
  for (const [a, b] of CONNECTIONS) {
    const [ax, ay] = pt(lm[a]);
    const [bx, by] = pt(lm[b]);
    ctx.moveTo(ax, ay);
    ctx.lineTo(bx, by);
  }
  ctx.stroke();
  ctx.globalAlpha = 1;
  ctx.fillStyle = "#fff";
  for (const p of lm) {
    const [x, y] = pt(p);
    ctx.beginPath();
    ctx.arc(x, y, 2.2, 0, Math.PI * 2);
    ctx.fill();
  }
}
