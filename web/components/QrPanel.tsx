"use client";

import { useEffect, useState } from "react";
import QRCode from "qrcode";

export function QrPanel({
  link,
  onClose,
  onScan,
  onNewSession,
}: {
  link: string;
  onClose?: () => void;
  onScan: () => void;
  onNewSession: () => void;
}) {
  const [svg, setSvg] = useState<string>("");
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    let alive = true;
    QRCode.toString(link, { type: "svg", margin: 1, errorCorrectionLevel: "M", color: { dark: "#0b0d12", light: "#ffffff" } })
      .then((s) => alive && setSvg(s))
      .catch(() => alive && setSvg(""));
    return () => {
      alive = false;
    };
  }, [link]);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(link);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      /* clipboard blocked; the link is still visible */
    }
  };

  return (
    <section className="qr-panel" aria-label="Link another device">
      <div className="qr-head">
        <h2>Link a device</h2>
        {onClose && (
          <button className="icon-btn" onClick={onClose} aria-label="Hide">
            ×
          </button>
        )}
      </div>
      <p className="muted">Scan once with the other device&apos;s camera. Both devices stay paired for 7 days.</p>
      <div className="qr-code" dangerouslySetInnerHTML={{ __html: svg }} />
      <button className="link-row" onClick={copy} title="Copy link">
        <span className="link-text">{link.replace(/^https?:\/\//, "")}</span>
        <span className="link-copy">{copied ? "Copied" : "Copy"}</span>
      </button>
      <button className="text-btn" onClick={onScan}>
        Or scan the other device&apos;s code with this camera
      </button>
      <button className="text-btn subtle" onClick={onNewSession}>
        Unpair and start a new session
      </button>
    </section>
  );
}
