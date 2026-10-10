// Chunked transfer of a batch of items through the relay.
//
//   sender → { type: "batch", batchId, items: [{ id, name, mime, size }] }
//   sender → { type: "chunk", batchId, itemId, data }   (base64, in order)
//   sender → { type: "batch-done", batchId }
//   receiver → { type: "received", batchId }
//
// Chunks keep every relay message small, so big videos and many photos go
// through without hitting the relay's per-message limit.

import { MAX_BATCH_BYTES, kindOf, makeItem, type Item } from "./items.ts";

const CHUNK_BYTES = 192 * 1024;
const MAX_BUFFERED = 4 * 1024 * 1024;
const MAX_ITEMS = 500;
const STALL_MS = 20_000;

type Msg = { type: string; [key: string]: unknown };
export type TransferIO = { send: (m: Msg) => boolean; buffered: () => number };
type Meta = { id: string; name: string; mime: string; size: number };

async function blobToBase64(blob: Blob): Promise<string> {
  const bytes = new Uint8Array(await blob.arrayBuffer());
  let bin = "";
  for (let i = 0; i < bytes.length; i += 0x8000) {
    bin += String.fromCharCode.apply(null, Array.from(bytes.subarray(i, i + 0x8000)));
  }
  return btoa(bin);
}

function base64ToBytes(b64: string): Uint8Array<ArrayBuffer> {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** Streams the items to the peer. Resolves with the batch id; rejects if the link drops. */
export async function sendBatch(
  items: Item[],
  io: TransferIO,
  batchId: string,
  onProgress: (sent: number, total: number) => void,
): Promise<void> {
  const total = items.reduce((n, it) => n + it.size, 0);
  const metas: Meta[] = items.map(({ id, name, mime, size }) => ({ id, name, mime, size }));
  if (!io.send({ type: "batch", batchId, items: metas })) throw new Error("not connected");

  let sent = 0;
  onProgress(0, total);
  for (const it of items) {
    for (let off = 0; off < it.size || (it.size === 0 && off === 0); off += CHUNK_BYTES) {
      while (io.buffered() > MAX_BUFFERED) await sleep(15);
      const data = await blobToBase64(it.blob.slice(off, off + CHUNK_BYTES));
      if (!io.send({ type: "chunk", batchId, itemId: it.id, data })) throw new Error("connection lost");
      sent += Math.min(CHUNK_BYTES, it.size - off);
      onProgress(sent, total);
      if (it.size === 0) break;
    }
  }
  if (!io.send({ type: "batch-done", batchId })) throw new Error("connection lost");
}

function cleanName(name: unknown): string {
  const s = typeof name === "string" ? name : "file";
  return s.replace(/[\\/:*?"<>|\u0000-\u001f]/g, "_").slice(0, 120) || "file";
}

function parseMetas(v: unknown): Meta[] | null {
  if (!Array.isArray(v) || v.length === 0 || v.length > MAX_ITEMS) return null;
  const metas: Meta[] = [];
  let total = 0;
  for (const m of v) {
    if (!m || typeof m !== "object") return null;
    const { id, name, mime, size } = m as Record<string, unknown>;
    if (typeof id !== "string" || id.length > 40 || typeof size !== "number" || !(size >= 0)) return null;
    const safeMime = typeof mime === "string" && /^[\w.+-]+\/[\w.+-]+$/.test(mime) ? mime.slice(0, 100) : "application/octet-stream";
    total += size;
    metas.push({ id, name: cleanName(name), mime: safeMime, size });
  }
  return total <= MAX_BATCH_BYTES ? metas : null;
}

type Incoming = {
  batchId: string;
  metas: Meta[];
  parts: Map<string, Uint8Array<ArrayBuffer>[]>;
  bytes: Map<string, number>;
  received: number;
  total: number;
  timer: ReturnType<typeof setTimeout> | undefined;
};

export type ReceiverEvents = {
  onStart: (batchId: string, count: number, total: number, names: string[]) => void;
  onProgress: (received: number, total: number) => void;
  onComplete: (batchId: string, items: Item[]) => void;
  onAbort: (reason: string) => void;
};

/** Reassembles incoming batches. `handle` returns true for transfer messages. */
export class BatchReceiver {
  private cur: Incoming | null = null;
  private ev: ReceiverEvents;
  constructor(ev: ReceiverEvents) {
    this.ev = ev;
  }

  handle(msg: Msg): boolean {
    switch (msg.type) {
      case "batch": {
        const metas = parseMetas(msg.items);
        if (!metas || typeof msg.batchId !== "string") {
          this.ev.onAbort("Incoming items were too large or invalid");
          return true;
        }
        this.reset();
        const total = metas.reduce((n, m) => n + m.size, 0);
        this.cur = {
          batchId: msg.batchId,
          metas,
          parts: new Map(metas.map((m) => [m.id, []])),
          bytes: new Map(metas.map((m) => [m.id, 0])),
          received: 0,
          total,
          timer: undefined,
        };
        this.arm();
        this.ev.onStart(msg.batchId, metas.length, total, metas.map((m) => m.name));
        return true;
      }
      case "chunk": {
        const c = this.cur;
        if (!c || msg.batchId !== c.batchId || typeof msg.itemId !== "string" || typeof msg.data !== "string") return true;
        const parts = c.parts.get(msg.itemId);
        const meta = c.metas.find((m) => m.id === msg.itemId);
        if (!parts || !meta) return true;
        let bytes: Uint8Array<ArrayBuffer>;
        try {
          bytes = base64ToBytes(msg.data);
        } catch {
          return true;
        }
        const have = c.bytes.get(meta.id)!;
        if (have + bytes.length > meta.size) bytes = bytes.slice(0, Math.max(0, meta.size - have));
        parts.push(bytes);
        c.bytes.set(meta.id, have + bytes.length);
        c.received += bytes.length;
        this.arm();
        this.ev.onProgress(c.received, c.total);
        return true;
      }
      case "batch-done": {
        const c = this.cur;
        if (!c || msg.batchId !== c.batchId) return true;
        this.reset();
        const items = c.metas.map((m) => {
          const kind = kindOf(m.mime);
          // Non-media files get a neutral type so they can never render as a page here.
          const blob = new Blob(c.parts.get(m.id)!, { type: kind === "file" ? "application/octet-stream" : m.mime });
          const item = makeItem(blob, m.name);
          return { ...item, mime: m.mime, kind };
        });
        this.ev.onComplete(c.batchId, items);
        return true;
      }
    }
    return false;
  }

  /** Drops a transfer in progress (e.g. the peer disconnected). */
  abort(reason: string) {
    if (!this.cur) return;
    this.reset();
    this.ev.onAbort(reason);
  }

  private arm() {
    if (!this.cur) return;
    clearTimeout(this.cur.timer);
    this.cur.timer = setTimeout(() => this.abort("Transfer stalled — ask the sender to try again"), STALL_MS);
  }

  private reset() {
    if (this.cur) clearTimeout(this.cur.timer);
    this.cur = null;
  }
}
