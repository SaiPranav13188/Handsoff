import { test } from "node:test";
import assert from "node:assert/strict";
import { GestureTracker, opennessRatio, type Landmark } from "./gesture.ts";

// Synthetic upright hand with realistic proportions: palm (wrist → middle MCP)
// 0.2 tall, fingers about as long as the palm when extended.
function hand(tipY: number, jitter = 0): Landmark[] {
  const lm: Landmark[] = Array.from({ length: 21 }, () => ({ x: 0.5, y: 0.7 }));
  lm[0] = { x: 0.5, y: 0.8 };
  const xs = [0.44, 0.48, 0.52, 0.56];
  [5, 9, 13, 17].forEach((i, k) => (lm[i] = { x: xs[k], y: 0.6 }));
  [8, 12, 16, 20].forEach((i, k) => (lm[i] = { x: xs[k] + jitter, y: tipY + jitter }));
  return lm;
}
const OPEN = hand(0.43); // fully extended fingers
const RELAXED = hand(0.47); // loosely open hand
const FIST = hand(0.6); // fingertips folded onto the knuckles

test("openness ratio separates open hand from fist", () => {
  assert.ok(opennessRatio(OPEN) > 0.95 && opennessRatio(OPEN) < 1.2, `open ratio ${opennessRatio(OPEN)}`);
  assert.ok(opennessRatio(RELAXED) > 0.82, `relaxed ratio ${opennessRatio(RELAXED)}`);
  assert.ok(opennessRatio(FIST) < 0.5, `fist ratio ${opennessRatio(FIST)}`);
});

test("open → fist emits a single grab, fist → open a single release", () => {
  const t = new GestureTracker();
  const events: string[] = [];
  for (let i = 0; i < 10; i++) t.update(OPEN);
  assert.equal(t.current, "open");
  for (let i = 0; i < 10; i++) {
    const f = t.update(FIST);
    if (f.event) events.push(f.event);
  }
  for (let i = 0; i < 10; i++) {
    const f = t.update(OPEN);
    if (f.event) events.push(f.event);
  }
  assert.deepEqual(events, ["grab", "release"]);
});

test("a one- or two-frame flicker does not trigger a gesture", () => {
  const t = new GestureTracker();
  for (let i = 0; i < 10; i++) t.update(OPEN);
  const events: string[] = [];
  for (const lm of [FIST, FIST, OPEN, OPEN, OPEN, FIST, OPEN, OPEN, OPEN]) {
    const f = t.update(lm);
    if (f.event) events.push(f.event);
  }
  assert.deepEqual(events, []);
  assert.equal(t.current, "open");
});

test("losing the hand drops state to none after a grace period", () => {
  const t = new GestureTracker({ lostAfter: 3 });
  for (let i = 0; i < 10; i++) t.update(OPEN);
  t.update(null);
  t.update(null);
  assert.equal(t.current, "open");
  t.update(null);
  assert.equal(t.current, "none");
});

test("first appearance as a fist does not count as a grab", () => {
  const t = new GestureTracker();
  const events: string[] = [];
  for (let i = 0; i < 10; i++) {
    const f = t.update(FIST);
    if (f.event) events.push(f.event);
  }
  assert.equal(t.current, "closed");
  assert.deepEqual(events, []);
});

test("a relaxed (not fully spread) open hand still grabs and releases", () => {
  const t = new GestureTracker();
  const events: string[] = [];
  for (const lm of [...Array(10).fill(RELAXED), ...Array(10).fill(FIST), ...Array(10).fill(RELAXED)]) {
    const f = t.update(lm);
    if (f.event) events.push(f.event);
  }
  assert.deepEqual(events, ["grab", "release"]);
});
