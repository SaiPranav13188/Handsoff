// Things you can send: photos, videos, or any other file, kept at original quality.

export type ItemKind = "image" | "video" | "file";

export type Item = {
  id: string;
  name: string;
  mime: string;
  size: number;
  kind: ItemKind;
  blob: Blob;
  /** Object URL for previews and downloads; released with releaseItems(). */
  url: string;
};

export const MAX_BATCH_BYTES = 200 * 1024 * 1024;

export function newId() {
  return Math.random().toString(36).slice(2, 10) + Date.now().toString(36).slice(-4);
}

export function kindOf(mime: string): ItemKind {
  // SVG can carry scripts, so it is handled as a plain file, never rendered inline.
  if (mime.startsWith("image/") && mime !== "image/svg+xml") return "image";
  if (mime.startsWith("video/")) return "video";
  return "file";
}

export function makeItem(blob: Blob, name: string, id = newId()): Item {
  const mime = blob.type || "application/octet-stream";
  return { id, name: name || "file", mime, size: blob.size, kind: kindOf(mime), blob, url: URL.createObjectURL(blob) };
}

export function itemsFromFiles(files: Iterable<File>): Item[] {
  return Array.from(files, (f) => makeItem(f, f.name));
}

export function releaseItems(items: Item[]) {
  for (const it of items) URL.revokeObjectURL(it.url);
}

/** Snapshot of the selfie camera, mirrored so it matches what the user saw. */
export async function itemFromVideo(video: HTMLVideoElement): Promise<Item> {
  const canvas = document.createElement("canvas");
  canvas.width = video.videoWidth;
  canvas.height = video.videoHeight;
  const ctx = canvas.getContext("2d")!;
  ctx.translate(canvas.width, 0);
  ctx.scale(-1, 1);
  ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
  const blob = await new Promise<Blob>((resolve, reject) =>
    canvas.toBlob((b) => (b ? resolve(b) : reject(new Error("snapshot failed"))), "image/jpeg", 0.9),
  );
  const stamp = new Date().toISOString().slice(0, 19).replace(/[:T]/g, "-");
  return makeItem(blob, `snapshot-${stamp}.jpg`);
}

export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
  return `${(n / 1024 / 1024).toFixed(n < 10 * 1024 * 1024 ? 1 : 0)} MB`;
}

export function extensionOf(name: string): string {
  const m = /\.([a-z0-9]{1,5})$/i.exec(name);
  return m ? m[1].toUpperCase() : "FILE";
}
