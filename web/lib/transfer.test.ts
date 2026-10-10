import { test } from "node:test";
import assert from "node:assert/strict";
import { makeItem } from "./items.ts";
import { BatchReceiver, sendBatch } from "./transfer.ts";

function bytes(n: number, seed: number) {
  const b = new Uint8Array(n);
  for (let i = 0; i < n; i++) b[i] = (i * 31 + seed) & 0xff;
  return b;
}

function pipe() {
  const events: string[] = [];
  let done: ((items: ReturnType<typeof makeItem>[]) => void) | null = null;
  const finished = new Promise<ReturnType<typeof makeItem>[]>((r) => (done = r));
  const rx = new BatchReceiver({
    onStart: (_id, count, total) => events.push(`start ${count} ${total}`),
    onProgress: () => {},
    onComplete: (_id, items) => done!(items),
    onAbort: (reason) => events.push(`abort ${reason}`),
  });
  // JSON round-trip, like the real relay.
  const io = { send: (m: object) => (rx.handle(JSON.parse(JSON.stringify(m))), true), buffered: () => 0 };
  return { io, events, finished, rx };
}

test("a batch of several items (photo, big video, empty file) arrives byte-for-byte", async () => {
  const photo = makeItem(new Blob([bytes(300_000, 1)], { type: "image/jpeg" }), "photo.jpg");
  const video = makeItem(new Blob([bytes(1_000_003, 2)], { type: "video/mp4" }), "clip.mp4");
  const empty = makeItem(new Blob([], { type: "text/plain" }), "empty.txt");
  const { io, events, finished } = pipe();
  const progress: number[] = [];
  await sendBatch([photo, video, empty], io, "b1", (sent) => progress.push(sent));
  const got = await finished;

  assert.deepEqual(events, [`start 3 ${300_000 + 1_000_003}`]);
  assert.deepEqual(got.map((i) => [i.name, i.kind, i.size]), [
    ["photo.jpg", "image", 300_000],
    ["clip.mp4", "video", 1_000_003],
    ["empty.txt", "file", 0],
  ]);
  assert.deepEqual(new Uint8Array(await got[1].blob.arrayBuffer()), bytes(1_000_003, 2));
  assert.equal(progress.at(-1), 300_000 + 1_000_003);
});

test("non-media files get a neutral blob type; unsafe names are cleaned", async () => {
  const page = makeItem(new Blob(["<script>alert(1)</script>"], { type: "text/html" }), "../evil:page.html");
  const { io, finished } = pipe();
  await sendBatch([page], io, "b2", () => {});
  const [got] = await finished;
  assert.equal(got.blob.type, "application/octet-stream");
  assert.equal(got.mime, "text/html");
  assert.equal(got.name, ".._evil_page.html");
});

test("an invalid or oversized batch announcement is rejected", () => {
  const { rx, events } = pipe();
  rx.handle({ type: "batch", batchId: "x", items: [{ id: "a", name: "huge", mime: "video/mp4", size: 10 * 1024 ** 3 }] });
  rx.handle({ type: "batch", batchId: "y", items: "nope" });
  assert.equal(events.filter((e) => e.startsWith("abort")).length, 2);
});
