"use client";

import { extensionOf, formatBytes, type Item } from "@/lib/items";

/** Up to three items shown as a fanned stack; the first item is on top. */
export function ItemStack({ items }: { items: Item[] }) {
  const shown = items.slice(0, 3);
  const total = items.reduce((n, it) => n + it.size, 0);
  return (
    <>
      {shown
        .map((it, i) => (
          <div key={it.id} className={`tile tile-${i}`} aria-hidden={i > 0}>
            <ItemPreview item={it} />
          </div>
        ))
        .reverse()}
      {items.length > 1 && (
        <span className="count-badge">
          {items.length} items · {formatBytes(total)}
        </span>
      )}
    </>
  );
}

function ItemPreview({ item }: { item: Item }) {
  if (item.kind === "image") {
    // eslint-disable-next-line @next/next/no-img-element
    return <img src={item.url} alt={item.name} draggable={false} />;
  }
  if (item.kind === "video") {
    return <video src={item.url} muted playsInline preload="metadata" />;
  }
  return (
    <div className="file-tile">
      <span className="file-ext">{extensionOf(item.name)}</span>
      <span className="file-name">{item.name}</span>
      <span className="file-size">{formatBytes(item.size)}</span>
    </div>
  );
}
