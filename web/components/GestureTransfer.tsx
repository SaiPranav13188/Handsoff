"use client";

import { useCallback, useEffect, useRef, useState, type CSSProperties } from "react";
import { GestureTracker, type HandState, type Landmark } from "@/lib/gesture";
import { drawHand } from "@/lib/drawHand";
import { MAX_BATCH_BYTES, formatBytes, itemFromVideo, itemsFromFiles, newId, releaseItems, type Item } from "@/lib/items";
import {
  deviceLabel,
  ensureSessionInUrl,
  fetchLanOrigin,
  isLoopbackHost,
  joinLink,
  rememberSession,
  sessionFromScan,
  startNewSession,
} from "@/lib/session";
import { registerServiceWorker, takeSharedFiles } from "@/lib/share";
import { BatchReceiver, sendBatch } from "@/lib/transfer";
import { useHands } from "@/lib/useHands";
import { useQrScanner } from "@/lib/useQrScanner";
import { useRelay, type RelayMessage } from "@/lib/useRelay";
import { ItemStack } from "./ItemStack";
import { QrPanel } from "./QrPanel";

type SendPhase = "idle" | "holding" | "flying" | "sending";
type ReceivePhase = "none" | "waiting" | "arriving";
type Origin = "local" | "received";
type QrMode = "auto" | "open" | "closed";
type Incoming = { batchId: string; count: number; total: number; received: number; ready: boolean; accepted: boolean };

const PALM_HOLD_MS = 350; // open palm must be held this long to accept incoming items
const FLY_MS = 900;
const ARRIVE_MS = 900;
const AUTO_KEY = "handsoff.autoAccept";

const HAND_COLORS: Record<HandState, string> = { none: "#8b93a7", open: "#5eead4", closed: "#fbbf24" };

function vibrate(ms: number) {
  try {
    navigator.vibrate?.(ms);
  } catch {
    /* not supported */
  }
}

const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? "" : "s"}`;
const describe = (items: { length: number }) => (items.length === 1 ? "the item" : `all ${items.length} items`);

export default function GestureTransfer() {
  const [room, setRoom] = useState<string | null>(null);
  const [items, setItems] = useState<Item[]>([]);
  const [origin, setOrigin] = useState<Origin>("local");
  const [sendPhase, setSendPhase] = useState<SendPhase>("idle");
  const [sendProgress, setSendProgress] = useState(0);
  const [incoming, setIncoming] = useState<Incoming | null>(null);
  const [receivePhase, setReceivePhase] = useState<ReceivePhase>("none");
  const [autoAccept, setAutoAccept] = useState(false);
  const [hand, setHand] = useState<HandState>("none");
  const [peerLabel, setPeerLabel] = useState<string | null>(null);
  const [toast, setToast] = useState<{ text: string; key: number } | null>(null);
  const [qrMode, setQrMode] = useState<QrMode>("auto");
  const [scanning, setScanning] = useState(false);
  const [dragging, setDragging] = useState(false);
  const [cameraOn, setCameraOn] = useState(true);
  const [debug, setDebug] = useState(false);
  const [log, setLog] = useState<string[]>([]);
  const [draft, setDraft] = useState("");
  const [linkOrigin, setLinkOrigin] = useState<string | undefined>(undefined);

  const videoRef = useRef<HTMLVideoElement>(null);
  const overlayRef = useRef<HTMLCanvasElement>(null);
  const cardRef = useRef<HTMLDivElement>(null);
  const meterRef = useRef<HTMLDivElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const trackerRef = useRef(new GestureTracker());
  const openSinceRef = useRef(0);
  const followRef = useRef({ x: 0, y: 0 });
  const prevPeersRef = useRef(0);
  const timersRef = useRef<ReturnType<typeof setTimeout>[]>([]);
  const acceptedRef = useRef(false);
  const completedRef = useRef<Item[] | null>(null);
  const progressAtRef = useRef(0);

  const later = (fn: () => void, ms: number) => {
    timersRef.current.push(setTimeout(fn, ms));
  };
  useEffect(() => () => timersRef.current.forEach(clearTimeout), []);

  const notify = useCallback((text: string) => setToast({ text, key: Date.now() }), []);
  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 2600);
    return () => clearTimeout(t);
  }, [toast]);

  const addLog = useCallback((line: string) => {
    setLog((l) => [...l.slice(-30), `${new Date().toLocaleTimeString()}  ${line}`]);
  }, []);

  // ── Tray of items to send ────────────────────────────────────────────────
  // The current tray lives in a ref too, so callbacks always see the latest list.
  const itemsRef = useRef<Item[]>([]);
  const originRef = useRef<Origin>("local");

  const replaceItems = useCallback((next: Item[], from: Origin) => {
    releaseItems(itemsRef.current.filter((c) => !next.includes(c)));
    itemsRef.current = next;
    originRef.current = from;
    setItems(next);
    setOrigin(from);
  }, []);

  const addItems = useCallback(
    (files: Iterable<File>) => {
      const added = itemsFromFiles(files);
      if (!added.length) return;
      // Received items are replaced (they've been delivered); local ones accumulate.
      const keep = originRef.current === "received" ? [] : itemsRef.current;
      const next = [...keep, ...added];
      if (next.reduce((n, it) => n + it.size, 0) > MAX_BATCH_BYTES) {
        releaseItems(added);
        notify(`That's over ${formatBytes(MAX_BATCH_BYTES)} — send fewer or smaller files`);
        return;
      }
      replaceItems(next, "local");
      notify(`${plural(next.length, "item")} ready — make a fist to grab`);
    },
    [notify, replaceItems],
  );

  // ── Session, Share-menu intake, settings ────────────────────────────────
  useEffect(() => {
    setRoom(ensureSessionInUrl());
    const params = new URLSearchParams(window.location.search);
    setDebug(params.has("debug"));
    try {
      setAutoAccept(localStorage.getItem(AUTO_KEY) === "1");
    } catch {
      /* storage blocked */
    }
    registerServiceWorker();

    const shared = params.get("shared");
    if (shared !== null) {
      const url = new URL(window.location.href);
      url.searchParams.delete("shared");
      window.history.replaceState(null, "", url);
    }
    if (shared === "1") {
      takeSharedFiles()
        .then((files) => (files.length ? addItems(files) : notify("Couldn't open the shared items — try sharing again")))
        .catch(() => notify("Couldn't open the shared items — try sharing again"));
    } else if (shared === "0") {
      notify("Couldn't receive the shared items — open Handsoff once, then share again");
    }
    // "localhost" in a QR code would point the phone at itself; use the Wi-Fi address instead.
    if (isLoopbackHost()) fetchLanOrigin().then((o) => o && setLinkOrigin(o));
  }, [addItems, notify]);

  const toggleAutoAccept = () => {
    setAutoAccept((on) => {
      try {
        localStorage.setItem(AUTO_KEY, on ? "0" : "1");
      } catch {
        /* storage blocked */
      }
      notify(on ? "Auto-accept off — open your palm to receive" : "Auto-accept on — items arrive instantly");
      return !on;
    });
  };

  // ── Receiving ────────────────────────────────────────────────────────────
  const sendRef = useRef<(m: RelayMessage) => boolean>(() => false);
  const autoAcceptRef = useRef(autoAccept);
  autoAcceptRef.current = autoAccept;

  const resetFollow = () => {
    followRef.current = { x: 0, y: 0 };
    cardRef.current?.style.setProperty("--hx", "0px");
    cardRef.current?.style.setProperty("--hy", "0px");
  };

  const reveal = useCallback(
    (batchId: string) => {
      const got = completedRef.current;
      if (!got) return;
      completedRef.current = null;
      acceptedRef.current = false;
      replaceItems(got, "received");
      setReceivePhase("arriving");
      setIncoming(null);
      resetFollow();
      sendRef.current({ type: "received", batchId });
      vibrate(30);
      later(() => setReceivePhase("none"), ARRIVE_MS);
    },
    [replaceItems],
  );

  const receiverRef = useRef<BatchReceiver | null>(null);
  if (!receiverRef.current) {
    receiverRef.current = new BatchReceiver({
      onStart: (batchId, count, total) => {
        addLog(`incoming ${count} item(s), ${formatBytes(total)}`);
        if (completedRef.current) releaseItems(completedRef.current);
        completedRef.current = null;
        acceptedRef.current = autoAcceptRef.current;
        openSinceRef.current = 0;
        setSendPhase((p) => (p === "holding" ? "idle" : p));
        setIncoming({ batchId, count, total, received: 0, ready: false, accepted: autoAcceptRef.current });
        setReceivePhase("waiting");
        vibrate(30);
      },
      onProgress: (received, total) => {
        const now = performance.now();
        if (now - progressAtRef.current < 100 && received < total) return;
        progressAtRef.current = now;
        setIncoming((cur) => (cur ? { ...cur, received } : cur));
      },
      onComplete: (batchId, got) => {
        addLog(`received ${got.length} item(s)`);
        completedRef.current = got;
        setIncoming((cur) => (cur ? { ...cur, received: cur.total, ready: true } : cur));
        if (acceptedRef.current) reveal(batchId);
      },
      onAbort: (reason) => {
        addLog(`transfer aborted: ${reason}`);
        setIncoming(null);
        setReceivePhase("none");
        notify(reason);
      },
    });
  }

  const acceptIncoming = useCallback(() => {
    const inc = stateRef.current.incoming;
    if (stateRef.current.receivePhase !== "waiting" || !inc) return;
    acceptedRef.current = true;
    if (completedRef.current) reveal(inc.batchId);
    else {
      setIncoming((cur) => (cur ? { ...cur, accepted: true } : cur));
      vibrate(15);
    }
  }, [reveal]);

  // ── Relay link (silent) ──────────────────────────────────────────────────
  const onMessage = useCallback(
    (msg: RelayMessage) => {
      if (receiverRef.current!.handle(msg)) return;
      switch (msg.type) {
        case "welcome":
        case "presence": {
          const peers = msg.peers as number;
          if (peers > 0 && (msg.type === "welcome" || peers > prevPeersRef.current)) {
            sendRef.current({ type: "hello", label: deviceLabel() });
          }
          if (peers === 0) {
            setPeerLabel(null);
            receiverRef.current!.abort("The other device disconnected");
          }
          if (peers > prevPeersRef.current && prevPeersRef.current === 0) {
            vibrate(15);
            setQrMode((m) => (m === "open" ? "auto" : m));
          }
          prevPeersRef.current = peers;
          addLog(`${msg.type}: ${peers} other device(s) in room`);
          break;
        }
        case "hello":
          if (typeof msg.label === "string") {
            setPeerLabel(msg.label.slice(0, 40));
            addLog(`peer is ${msg.label}`);
          }
          break;
        case "text":
          if (typeof msg.text === "string") addLog(`peer says: ${msg.text.slice(0, 200)}`);
          break;
        case "received":
          addLog(`peer confirmed batch ${String(msg.batchId)}`);
          notify("Delivered ✓");
          break;
      }
    },
    [addLog, notify],
  );

  const { status: linkStatus, peers, send, buffered } = useRelay(room, onMessage);
  sendRef.current = send;

  // Hide the auto QR once linked; bring it back if the peer leaves.
  const showQr = !scanning && (qrMode === "open" || (qrMode === "auto" && peers === 0));

  // ── Gesture actions (sending) ────────────────────────────────────────────
  const state = { sendPhase, receivePhase, items, incoming, peers, peerLabel };
  const stateRef = useRef(state);
  stateRef.current = state;

  const grab = useCallback(() => {
    const s = stateRef.current;
    if (s.sendPhase !== "idle" || s.receivePhase !== "none" || !s.items.length) return;
    setSendPhase("holding");
    vibrate(20);
  }, []);

  const release = useCallback(() => {
    const s = stateRef.current;
    if (s.sendPhase !== "holding" || !s.items.length) return;
    if (s.peers === 0) {
      setSendPhase("idle");
      resetFollow();
      notify("No device linked yet — scan the QR code first");
      setQrMode("open");
      return;
    }
    const batch = s.items;
    const batchId = newId();
    const to = s.peerLabel ?? "linked device";
    setSendPhase("flying");
    setSendProgress(0);
    vibrate(25);
    addLog(`sending ${batch.length} item(s)`);

    const flown = new Promise((r) => later(() => r(null), FLY_MS));
    const transfer = sendBatch(batch, { send: sendRef.current, buffered }, batchId, (sent, total) => {
      setSendProgress(total ? sent / total : 1);
    });
    flown.then(() => setSendPhase((p) => (p === "flying" ? "sending" : p)));
    Promise.all([flown, transfer])
      .then(() => notify(`Sent ${plural(batch.length, "item")} to ${to}`))
      .catch(() => notify("Sending failed — the connection dropped"))
      .finally(() => {
        setSendPhase("idle");
        resetFollow();
      });
  }, [addLog, buffered, notify]);

  const cancelHold = useCallback(() => {
    if (stateRef.current.sendPhase !== "holding") return;
    setSendPhase("idle");
    resetFollow();
  }, []);

  // ── Per-frame hand processing ────────────────────────────────────────────
  const handRef = useRef<HandState>("none");
  const onFrame = useCallback(
    (lm: Landmark[] | null, aspect: number) => {
      const f = trackerRef.current.update(lm, aspect);
      if (overlayRef.current) drawHand(overlayRef.current, lm, HAND_COLORS[f.state]);
      if (meterRef.current) {
        const pct = Number.isFinite(f.openness) ? Math.max(0, Math.min(1, (f.openness - 0.3) / 0.9)) : 0;
        meterRef.current.style.transform = `scaleX(${pct.toFixed(3)})`;
      }
      if (f.state !== handRef.current) {
        handRef.current = f.state;
        setHand(f.state);
      }

      const s = stateRef.current;

      // Receiver: an open palm held briefly confirms "ready to receive".
      if (s.receivePhase === "waiting") {
        if (f.state === "open") {
          const now = performance.now();
          if (!openSinceRef.current) openSinceRef.current = now;
          else if (now - openSinceRef.current >= PALM_HOLD_MS) {
            openSinceRef.current = 0;
            acceptIncoming();
          }
        } else openSinceRef.current = 0;
        return;
      }

      if (f.event === "grab") grab();
      else if (f.event === "release") release();
      else if (s.sendPhase === "holding" && f.state === "none") cancelHold();

      // While held, the stack follows the palm (mirrored to match the preview).
      if (s.sendPhase === "holding" && f.palm && cardRef.current) {
        const stage = cardRef.current.parentElement!;
        const tx = (0.5 - f.palm.x) * stage.clientWidth * 0.9;
        const ty = (f.palm.y - 0.5) * stage.clientHeight * 0.9;
        const p = followRef.current;
        p.x += (tx - p.x) * 0.35;
        p.y += (ty - p.y) * 0.35;
        cardRef.current.style.setProperty("--hx", `${p.x.toFixed(1)}px`);
        cardRef.current.style.setProperty("--hy", `${p.y.toFixed(1)}px`);
      }
    },
    [acceptIncoming, cancelHold, grab, release],
  );

  const { status: camStatus, error: camError } = useHands(videoRef, cameraOn, onFrame);

  // Restart the camera pipeline after an error.
  const retryCamera = () => {
    setCameraOn(false);
    setTimeout(() => setCameraOn(true), 50);
  };

  // ── QR scanning (join another device's session) ─────────────────────────
  useQrScanner(videoRef, scanning && camStatus === "ready", (text) => {
    const id = sessionFromScan(text);
    if (!id) return;
    setScanning(false);
    if (id === room) {
      notify("That's this device's own code");
      return;
    }
    const url = new URL(window.location.href);
    url.searchParams.set("s", id);
    window.history.replaceState(null, "", url);
    rememberSession(id);
    prevPeersRef.current = 0;
    setRoom(id);
    setQrMode("auto");
    vibrate(20);
  });

  // ── Keyboard, paste and drag-and-drop ───────────────────────────────────
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.target as HTMLElement)?.closest("input, textarea")) return;
      if (e.code === "Space") {
        e.preventDefault();
        if (stateRef.current.receivePhase === "waiting") acceptIncoming();
        else if (stateRef.current.sendPhase === "holding") release();
        else grab();
      } else if (e.key === "Escape") {
        cancelHold();
        setScanning(false);
      }
    };
    const onPaste = (e: ClipboardEvent) => {
      if ((e.target as HTMLElement)?.closest("input, textarea")) return;
      const files = e.clipboardData?.files;
      if (files?.length) {
        e.preventDefault();
        addItems(files);
      }
    };
    let depth = 0;
    const hasFiles = (e: DragEvent) => Array.from(e.dataTransfer?.types ?? []).includes("Files");
    const onDragEnter = (e: DragEvent) => {
      if (!hasFiles(e)) return;
      depth++;
      setDragging(true);
    };
    const onDragLeave = (e: DragEvent) => {
      if (!hasFiles(e)) return;
      depth = Math.max(0, depth - 1);
      if (!depth) setDragging(false);
    };
    const onDragOver = (e: DragEvent) => {
      if (hasFiles(e)) e.preventDefault();
    };
    const onDrop = (e: DragEvent) => {
      if (!hasFiles(e)) return;
      e.preventDefault();
      depth = 0;
      setDragging(false);
      if (e.dataTransfer?.files.length) addItems(e.dataTransfer.files);
    };
    window.addEventListener("keydown", onKey);
    window.addEventListener("paste", onPaste);
    window.addEventListener("dragenter", onDragEnter);
    window.addEventListener("dragleave", onDragLeave);
    window.addEventListener("dragover", onDragOver);
    window.addEventListener("drop", onDrop);
    return () => {
      window.removeEventListener("keydown", onKey);
      window.removeEventListener("paste", onPaste);
      window.removeEventListener("dragenter", onDragEnter);
      window.removeEventListener("dragleave", onDragLeave);
      window.removeEventListener("dragover", onDragOver);
      window.removeEventListener("drop", onDrop);
    };
  }, [acceptIncoming, addItems, cancelHold, grab, release]);

  // ── Other item sources & actions ────────────────────────────────────────
  const snap = async () => {
    const v = videoRef.current;
    if (!v || camStatus !== "ready" || !v.videoWidth) return;
    const shot = await itemFromVideo(v);
    addItems([new File([shot.blob], shot.name, { type: shot.mime })]);
    releaseItems([shot]);
    vibrate(10);
  };

  const saveAll = async () => {
    for (const it of items) {
      const a = document.createElement("a");
      a.href = it.url;
      a.download = it.name;
      document.body.appendChild(a);
      a.click();
      a.remove();
      await new Promise((r) => setTimeout(r, 250));
    }
  };

  const files = items.map((it) => new File([it.blob], it.name, { type: it.mime }));
  const canShare = typeof navigator !== "undefined" && !!navigator.canShare && items.length > 0 && navigator.canShare({ files });
  const shareOut = () => {
    navigator.share({ files }).catch(() => {
      /* cancelled */
    });
  };

  const clearItems = () => replaceItems([], "local");

  // ── Derived UI text ──────────────────────────────────────────────────────
  const peerName = peerLabel ?? "the other device";
  const camFailed = camStatus === "denied" || camStatus === "error" || camStatus === "unsupported";
  const inPct = incoming && incoming.total ? Math.round((incoming.received / incoming.total) * 100) : 100;
  let hint: string;
  if (receivePhase === "waiting" && incoming) {
    const what = plural(incoming.count, "item");
    if (incoming.accepted) hint = `Receiving ${what}… ${inPct}%`;
    else
      hint =
        camStatus === "ready"
          ? `${what} from ${peerName} — open your palm to receive`
          : `${what} from ${peerName} — tap to receive`;
  } else if (receivePhase === "arriving") hint = "Received";
  else if (sendPhase === "flying" || sendPhase === "sending") hint = `Sending… ${Math.round(sendProgress * 100)}%`;
  else if (camFailed) hint = items.length ? `${camError ?? "Camera unavailable"} — tap the stack to send instead` : camError ?? "Camera unavailable";
  else if (camStatus !== "ready") hint = "Starting camera…";
  else if (scanning) hint = "Hold the other device's QR code up to this camera";
  else if (sendPhase === "holding")
    hint = peers > 0 ? `Open your hand to send to ${peerName}` : "Open your hand to drop — no device linked";
  else if (items.length) hint = `Open hand, then make a fist to grab ${describe(items)} (or tap)`;
  else hint = "Add photos or files, then grab them with your hand";

  // "connecting" is deliberately shown the same as idle: no visible connect step.
  const linkText =
    linkStatus === "offline"
      ? "Offline — retrying"
      : peers > 0
        ? `Linked · ${peerLabel ?? `${peers} device${peers > 1 ? "s" : ""}`}`
        : "Waiting for a device";
  const linkClass = linkStatus === "offline" ? "offline" : peers > 0 ? "linked" : "alone";

  const cardClass = [
    "card",
    "stack",
    items.length > 1 && "multi",
    sendPhase === "holding" && "holding",
    sendPhase === "flying" && "flying",
    sendPhase === "sending" && "sending",
    receivePhase === "arriving" && "arriving",
    receivePhase === "waiting" && "dimmed",
  ]
    .filter(Boolean)
    .join(" ");
  const busy = sendPhase !== "idle" || receivePhase !== "none";

  return (
    <main className="app">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark" aria-hidden>
            ✋
          </span>
          Handsoff
        </div>
        <div className={`link-pill ${linkClass}`} role="status" aria-live="polite">
          <span className="dot" />
          {linkText}
        </div>
      </header>

      <section className="stage">
        {items.length > 0 ? (
          <div
            ref={cardRef}
            key={items[0].id}
            className={cardClass}
            onClick={() => {
              // Tap fallback: tap to grab, tap again to send.
              if (stateRef.current.sendPhase === "holding") release();
              else grab();
            }}
          >
            <ItemStack items={items} />
            {sendPhase === "sending" && (
              <div className="send-progress">
                <div style={{ transform: `scaleX(${sendProgress.toFixed(3)})` }} />
              </div>
            )}
          </div>
        ) : (
          <button className="dropzone" onClick={() => fileRef.current?.click()}>
            <span className="dropzone-plus" aria-hidden>
              +
            </span>
            <span className="dropzone-title">Add photos, videos or files</span>
            <span className="dropzone-sub">Pick several at once · drop or paste them here</span>
          </button>
        )}

        {items.length > 0 && !busy && (
          <div className="item-actions">
            {origin === "received" ? (
              <>
                <button onClick={saveAll}>{items.length > 1 ? "Save all" : "Save"}</button>
                {canShare && <button onClick={shareOut}>Share</button>}
              </>
            ) : (
              <button onClick={() => fileRef.current?.click()}>+ Add more</button>
            )}
            <button className="ghost" onClick={clearItems}>
              Clear
            </button>
          </div>
        )}

        {receivePhase === "waiting" && incoming && (
          <button
            className={`incoming ${incoming.accepted ? "accepted" : ""}`}
            onClick={acceptIncoming}
            aria-label="Receive incoming items"
            style={{ "--pct": `${inPct}%` } as CSSProperties}
          >
            <span className="ring" />
            <span className="ring r2" />
            <span className="incoming-core">
              <strong>{incoming.count}</strong>
              <small>{incoming.count === 1 ? "item" : "items"}</small>
              <small>{formatBytes(incoming.total)}</small>
            </span>
            <span className="palm" aria-hidden>
              ✋
            </span>
          </button>
        )}

        <p className="hint" key={hint}>
          {hint}
          {(camStatus === "denied" || camStatus === "error") && receivePhase === "none" && (
            <button className="text-btn inline" onClick={retryCamera}>
              Retry
            </button>
          )}
        </p>

        {showQr && room && (
          <QrPanel
            link={joinLink(room, linkOrigin)}
            onClose={() => setQrMode("closed")}
            onNewSession={() => {
              prevPeersRef.current = 0;
              setPeerLabel(null);
              setRoom(startNewSession());
              notify("Started a new session — scan the new code to pair");
            }}
            onScan={() => {
              setScanning(true);
            }}
          />
        )}
      </section>

      <aside className={`cam ${scanning ? "scanning" : ""}`}>
        <video ref={videoRef} playsInline muted />
        <canvas ref={overlayRef} />
        {scanning && <div className="scan-frame" />}
        <div className="cam-foot">
          <span className={`hand-chip ${hand}`}>{hand === "open" ? "✋ Open" : hand === "closed" ? "✊ Grab" : "No hand"}</span>
          <div className="meter">
            <div ref={meterRef} className="meter-fill" />
          </div>
        </div>
        {scanning && (
          <button className="cam-cancel" onClick={() => setScanning(false)}>
            Cancel
          </button>
        )}
      </aside>

      <nav className="toolbar">
        <button onClick={() => fileRef.current?.click()}>Add</button>
        <button onClick={snap} disabled={camStatus !== "ready"}>
          Snap
        </button>
        <button onClick={() => setQrMode(showQr ? "closed" : "open")} className={showQr ? "active" : ""}>
          Link
        </button>
        <button onClick={() => setScanning((s) => !s)} disabled={camStatus !== "ready"} className={scanning ? "active" : ""}>
          Scan
        </button>
        <button onClick={toggleAutoAccept} className={autoAccept ? "active" : ""} aria-pressed={autoAccept} title="Receive without the palm gesture">
          Auto
        </button>
        <input
          ref={fileRef}
          type="file"
          multiple
          hidden
          onChange={(e) => {
            if (e.target.files?.length) addItems(e.target.files);
            e.target.value = "";
          }}
        />
      </nav>

      {dragging && (
        <div className="drop-overlay" aria-hidden>
          Drop to add
        </div>
      )}

      {toast && (
        <div className="toast" key={toast.key} role="status">
          {toast.text}
        </div>
      )}

      {debug && (
        <section className="debug">
          <h3>Relay debug · room {room}</h3>
          <div className="debug-log">
            {[...log].reverse().map((l, i) => (
              <div key={i}>{l}</div>
            ))}
          </div>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              if (!draft.trim()) return;
              const ok = send({ type: "text", text: draft.trim() });
              addLog(ok ? `you said: ${draft.trim()}` : "not connected");
              setDraft("");
            }}
          >
            <input value={draft} onChange={(e) => setDraft(e.target.value)} placeholder="Text message to peer" />
            <button type="submit">Send</button>
          </form>
        </section>
      )}
    </main>
  );
}
