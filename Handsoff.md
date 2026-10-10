# Gesture Transfer — Hand-Gesture Image Sharing App

## 1. Overview

A web app that lets two devices transfer an image between them using hand gestures captured via webcam — no typing, no manual pairing step visible to the user. Inspired by Huawei's **Air Gesture File Transfer** (aka "AirDrop Gesture" / "AI Teleportation") feature on the Mate 70/Mate 60/Pura 70 series running HarmonyOS NEXT.

**Reference behavior (from Huawei's feature):**
- A user makes a **grab gesture** over an image to "pick it up"
- A **release/open-hand gesture** sends it to a nearby device
- The whole interaction takes 3–5 seconds and *feels* connectionless — but this is because both devices are already silently paired in the background (same account, same Wi-Fi) before the gesture happens. The gesture is the only visible step; the connection already exists and is just idle.

This project reproduces that illusion with open tools: gesture detection runs client-side, and devices are silently linked via a lightweight relay the moment they join the same session — so by the time a gesture happens, there's no visible "connecting..." step.

---

## 2. Core concept: what "connectionless" actually means

True zero-connection transfer isn't physically possible — data has to travel over *some* transport. What's achievable is **zero manual setup**: no room code to type, no visible pairing UI. This is done via:

- **Auto-join on load** — both devices open the same link (shared via QR code), and a WebSocket connection opens silently in the background the moment the page loads
- **Browser limitation to design around** — browsers can't do raw Bluetooth/mDNS discovery of unknown nearby devices for privacy/security reasons (`navigator.bluetooth` requires an explicit picker). A fully "discover any nearby phone automatically" version would require a **native app** (React Native/Flutter) with BLE permissions, not a website. This spec targets the browser-based version: automatic *within a shared session*, not automatic discovery of random nearby strangers.

---

## 3. Full pipeline

```
Device A opens page → generates QR code → WebSocket opens (idle)
Device B scans QR → joins same WebSocket channel (idle)

[Both devices now silently connected, no visible setup]

Device A: camera → MediaPipe → grab detected → image captured
Device A: camera → MediaPipe → release detected → image sent via WebSocket
Device B: WebSocket receives image → camera → MediaPipe → palm-open detected → image displayed
```

---

## 4. Gesture detection (built — Step 1 complete)

- **MediaPipe Hands** runs entirely client-side (webcam feed never leaves the device for this part) — detects 21 hand landmark points in real time
- **Grab vs. open** is computed as a "curl score": average distance from each fingertip to the palm center, normalized by hand size (wrist-to-middle-finger-MCP span)
  - High curl (fingertips close to palm) → **grab**
  - Low curl (fingers extended) → **open**
- Smoothing (exponential moving average) + a minimum consecutive-frame streak before a gesture is accepted, to avoid flicker/false positives on noisy frames
- Status built so far: grab/release gestures are **working and confirmed** on-device (tested via local `file://` HTML, after working around a camera-permission restriction inside claude.ai's artifact preview iframe)

---

## 5. Device-to-device transfer (next build step)

- **Room/session generation** — on page load, Device A generates a short random session ID
- **QR code** displaying a join link containing that session ID — Device B scans it, no manual entry
- **WebSocket relay (backend)** — a lightweight server (Node.js or Python) that:
  - Accepts connections, assigns them to a room by session ID
  - Relays any message from one device in a room to the other device(s) in that room
  - Does not store the image — purely a live relay (keep it stateless and ephemeral for v1)
- **Wiring gesture → transfer:**
  - Device A: grab captures the current image into memory; release gesture triggers a `send` message over the open socket, with the image payload
  - Device B: already listening on the socket; on message received, waits for an open-palm gesture (confirming "ready to receive") before displaying the incoming image

---

## 6. Tech stack

- **Frontend:** Next.js (Vercel) — camera access, MediaPipe gesture detection and UI, QR code generation/scanning
- **Backend:** Node.js or Python WebSocket server, deployed on **Render**
- **Database:** likely **not needed for v1** (transfer is live/ephemeral, no persistence required). If transfer history/logging is added later, Supabase or Neon (Postgres) would hold session logs only — never the image itself, to keep the relay stateless
- **Gesture detection library:** MediaPipe Hands (client-side, via CDN, no server round-trip)

---

## 7. Build order

1. **Gesture detection only** ✅ *(done)* — grab/release reliably detected on a single device, with visual feedback (mock image picks up / flies away)
2. **Two-device room (text only)** — basic WebSocket room join; confirm two devices can exchange a simple text message before adding image payloads
3. **QR-code auto-join** — generate/display/scan flow so no manual code entry is needed
4. **Wire gesture → real transfer** — grab+release on Device A sends the actual captured image to Device B over the socket
5. **Polish** — visual "fly toward edge" animation on send, palm-open confirmation on receive, handling gesture false positives, connection-state indicator (subtle, not a visible "connecting" step)

---

## 8. Known constraints & workarounds

- **Camera permission inside embedded previews:** testing directly inside claude.ai's artifact preview can silently block `getUserMedia()` even when OS/browser-level permissions are fine, because the preview may run in a sandboxed frame without camera permission passed through. Workaround used: download the HTML and open it directly as a local `file://` page, or (for the real deployed version) host it on Vercel/Render directly — this issue won't exist once the app is deployed at its own URL.
- **No true "discover any nearby device" automation** without a native app — browser-based version requires both devices to explicitly join the same session (via QR scan), not full ambient discovery.
- **Security for v1:** anyone with the QR code/session link can join the room. Fine for a demo; a production version would want short-lived session expiry and/or account-based pairing (closer to how Huawei requires the same account on both devices).

---

## 9. Why this is a strong portfolio project

- Combines **computer vision** (gesture detection) with **real-time networking** (WebSocket sync) — a different skill combination than your other projects (RoadmapAI, CineAI, Tavudu), broadening your demonstrated range
- Visually striking in a live demo — much more memorable than another CRUD-style app
- Has a genuine technical story to tell in interviews: the gesture-detection tuning (curl score, smoothing, debounce), the "connectionless" UX illusion and what's actually happening underneath, and the browser security constraints that shaped the architecture (BLE/mDNS restrictions forcing the QR-auto-join design)
