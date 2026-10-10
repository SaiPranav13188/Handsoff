# Handsoff — Gesture Transfer

## 📲 Download the Android app

**[⬇ Download Handsoff-v1.0.0.apk](https://github.com/SaiPranav13188/Handsoff/releases/download/v1.0.0/Handsoff-v1.0.0.apk)** (82.5 MB, Android 10+) · [Release notes](https://github.com/SaiPranav13188/Handsoff/releases/tag/v1.0.0)

Install it on two phones, allow "Install unknown apps", pair once with a 👊 fist bump, then show fingers → fist → open hand to send photos. It works phone-to-phone over Bluetooth and Wi-Fi Direct, with no internet needed. See [android/README.md](android/README.md) for all gestures.

---

Move a photo between two devices with your hands. Make a fist over the photo to grab it, open your hand to send it. On the other device, open your palm to receive it. This follows the spec in [Handsoff.md](Handsoff.md).

- **Gesture detection:** MediaPipe Hands, loaded from a CDN and run entirely in the browser. It computes a curl score with EMA smoothing, hysteresis, and a 5-frame streak before a gesture counts.
- **Silent pairing:** the page creates a session ID, shows it as a QR code, and opens a WebSocket in the background. The other device scans the QR code and is linked. There is no "connecting…" step.
- **Relay:** a small Node WebSocket server that forwards messages between devices in the same room. It never stores images.

```
android/  Native Android app: phone-to-phone over Nearby Connections (no Wi-Fi/internet needed)
server/   WebSocket relay (Node + ws)         → deploy on Render
web/      Next.js app (camera, gestures, UI)  → deploy on Vercel
scripts/  run.js: local server (app + relay, HTTP for the laptop and HTTPS for phones)
```

**For phone-to-phone transfer, use the Android app.** See [android/README.md](android/README.md). It pairs once, receives in the background, saves to the Gallery, and works with no network at all.

## Run it locally

Requires Node 18+.

```bash
npm run setup      # install dependencies (first time only)
npm run build      # build the web app
npm start          # start the app and relay together
```

`npm run dev` does the same in development mode (live reload), but it uses much more memory.

It prints two addresses:

- **On this computer:** http://localhost:3000
- **On your phone:** https://<your-wifi-ip>:3443. The QR code on the laptop already points here.

Open http://localhost:3000. The URL gets a `?s=<session>` added. Scan the QR code with your phone, or open the same URL in a second browser window. The status pill changes to **Linked**.

### Using it

| Device A (sender)                              | Device B (receiver)                                   |
| ---------------------------------------------- | ----------------------------------------------------- |
| Add items, then open hand → **make a fist**    | —                                                     |
| The stack of items follows your hand           | —                                                     |
| **Open hand**: the stack flies away and sends  | Orb with a progress ring: "3 items — open your palm"  |
| "Delivered ✓"                                  | **Open palm** (hold ~⅓ s): items fly in → Save all / Share |

- **Several items at once, of any type:** photos, videos, PDFs, documents (up to 200 MB per send). They're sent in small chunks with a progress bar.
- **Ways to add items:** **Add** (multi-select), **Snap** (camera photo), drag and drop onto the page, or **paste** (Ctrl+V). On Android, use **Share → Handsoff** from the Gallery or Files app (see below).
- **Auto** turns on auto-accept: items arrive straight away, without the palm gesture.
- **Link** shows the QR code, and **Scan** reads the other device's code. Pairing is remembered for 7 days.
- No camera? Tap the stack to grab it, tap again to send, and tap the orb to receive (or press **Space**).
- Add `?debug` to the URL for a relay log and a text-message box. This is the Step 2 "text only" check from the spec.

### Testing on phones (same Wi-Fi)

Phones only allow the camera over **HTTPS**, so the local server also serves HTTPS on port 3443, with the relay on the same address. It uses a self-signed certificate made on first start and saved in `.cert/`.

1. Connect the phone and the laptop to the **same Wi-Fi**.
2. Scan the QR code. The phone warns that the connection is **not private**. Tap **Advanced → Proceed**. This is expected for a local certificate.
3. Allow the camera.

If the phone can't reach the address, check that Windows Firewall allows Node.js. Also check that the Wi-Fi doesn't block devices from talking to each other (some office and college networks do). In that case, use a phone hotspot or deploy the app.

## Tests

```bash
npm test
```

- `server/test.js` checks that two clients in a room exchange text and a 4 MB payload, that rooms are isolated, and that presence updates arrive.
- `web/lib/transfer.test.ts` checks that a mixed batch (photo, 1 MB video, empty file) arrives byte-for-byte, that risky file types are neutralized, and that oversized batches are rejected.
- `web/lib/gesture.test.ts` checks that grab and release fire once each, that 1–2 frame flicker is ignored, that a lost hand drops to "none", and that a hand appearing already closed is not a grab.

## Share straight from the Gallery (Android)

Once the app is installed to the home screen, **Handsoff** appears in the **Share** menu of the Gallery, Files and other apps. You can select several items:

1. Pair once: scan the laptop's QR code with the phone. Pairing is remembered for 7 days on both devices.
2. In Chrome on the phone, open the menu (⋮) and choose **Add to Home screen** / **Install app**.
3. From then on: **Gallery → select photos → Share → Handsoff**. Handsoff opens with them loaded and already linked, so you only need to make a fist and then open your hand.

This needs a **trusted HTTPS address**, so it works on the deployed app but not with the self-signed local certificate. iPhones don't support adding apps to the Share menu (Web Share Target).

How it works: the [web app manifest](web/app/manifest.ts) declares a `share_target`. The [service worker](web/public/sw.js) catches the shared photo, parks it in a cache, and opens `/?shared=1`. The page then loads it ([web/lib/share.ts](web/lib/share.ts)).

## Deploy (Render, one free service)

The app and the relay run on one address ([render.yaml](render.yaml)):

1. Push this folder to a GitHub repository.
2. On [render.com](https://render.com), choose **New → Blueprint** and pick the repo. It reads `render.yaml` and creates the **handsoff** web service.
3. Open the `https://handsoff-xxxx.onrender.com` address it gives you on the laptop and the phone.

Render's free plan sleeps after about 15 minutes idle, so the first visit afterwards takes about 30 s to wake up.

You can still split the app across services as the spec describes: the `server/` folder deploys on its own as the relay, and `web/` on Vercel with `NEXT_PUBLIC_RELAY_URL=wss://<relay>/ws`. Relay environment variables: `ALLOWED_ORIGINS`, `MAX_PAYLOAD_BYTES` (default 12 MB), and `MAX_PEERS_PER_ROOM` (default 8).

## Known limits (v1)

- Anyone with the link or QR code can join the room. Remembered pairings expire after 7 days, and there are no accounts (spec §8).
- Discovery only works within a shared session. Browsers can't scan for nearby devices.
- Render's free tier sleeps when idle, so the first connection after a pause can take about 30 s. The app keeps retrying quietly.
