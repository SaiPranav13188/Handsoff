// Handsoff service worker: receives files from the system Share menu.
const INBOX_CACHE = "handsoff-share-inbox";
const INDEX_KEY = "/__shared/index";

self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== "POST" || url.pathname !== "/share-target") return;

  event.respondWith(
    (async () => {
      try {
        const form = await event.request.formData();
        const files = form.getAll("files").filter((f) => f && typeof f !== "string");
        if (files.length) {
          // Replace whatever an earlier share left behind.
          await caches.delete(INBOX_CACHE);
          const cache = await caches.open(INBOX_CACHE);
          const index = [];
          for (let i = 0; i < files.length; i++) {
            const key = `/__shared/${i}`;
            await cache.put(key, new Response(files[i], { headers: { "content-type": files[i].type || "application/octet-stream" } }));
            index.push({ key, name: files[i].name || `file-${i + 1}`, type: files[i].type || "" });
          }
          await cache.put(INDEX_KEY, new Response(JSON.stringify(index), { headers: { "content-type": "application/json" } }));
          return Response.redirect("/?shared=1", 303);
        }
      } catch {
        /* fall through */
      }
      return Response.redirect("/?shared=0", 303);
    })(),
  );
});
