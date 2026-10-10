"use client";

// Share menu → Handsoff (Web Share Target, Android/Chrome).
// The service worker (public/sw.js) catches the shared files, parks them in a
// cache, and redirects to /?shared=1. The page then picks them up from there.

const INBOX_CACHE = "handsoff-share-inbox";
const INDEX_KEY = "/__shared/index";

export function registerServiceWorker() {
  if (!("serviceWorker" in navigator) || !window.isSecureContext) return;
  navigator.serviceWorker.register("/sw.js").catch(() => {
    /* e.g. self-signed certificate: the app still works, just without the Share menu */
  });
}

/** Takes (and clears) the files the service worker parked, if any. */
export async function takeSharedFiles(): Promise<File[]> {
  if (!("caches" in window)) return [];
  const cache = await caches.open(INBOX_CACHE);
  const indexRes = await cache.match(INDEX_KEY);
  if (!indexRes) return [];
  const index: { key: string; name: string; type: string }[] = await indexRes.json();
  const files: File[] = [];
  for (const entry of index) {
    const res = await cache.match(entry.key);
    if (!res) continue;
    const blob = await res.blob();
    files.push(new File([blob], entry.name, { type: entry.type || blob.type }));
  }
  await caches.delete(INBOX_CACHE);
  return files;
}
