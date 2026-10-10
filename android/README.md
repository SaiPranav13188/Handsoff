# Handsoff for Android: phone-to-phone gesture transfer

Send photos, videos and files from one Android phone to another. Grab them with your hand, then open your hand to send. The phones connect directly to each other, so no Wi-Fi network, internet, server or laptop is needed.

## Gestures

| Gesture | What it does |
|---|---|
| **Hold up 1–5 fingers** (nothing picked yet) | Picks your **latest 1–5 photos/videos**, so you don't need to open the Gallery. Hold still for about ¾ s; a bar fills up while you hold. |
| **Hold up 1–4 fingers** (items picked) | Chooses **who receives**: the phone numbered 1–4 under *My phones*. |
| **Open hand, then fist** | Grabs the stack. |
| **Open your hand again** | Sends it (the stack flies away). |
| **Flick ← → ↑ while opening your hand** | Sends to phone #1 (←), #2 (→) or #3 (↑): like throwing it at someone. The arrows are shown on the phone chips. |
| **Swipe ↔ in the air** (several items picked) | Flips through them. **👌 Pinch** removes the one on top. |
| **👋 Wave** (within 5 s of sending) | Unsends: cancels the transfer, or removes the items from the other phone's Gallery if they already arrived. There's also an **Undo** button. |
| **👍 or ✌️** (after receiving) | Sends that reaction back; the sender gets *"Rahul reacted 👍"*. |
| **👌 Pinch** (nothing picked) | **Clipboard teleport**: whatever you copied (a link, an address, an OTP) lands on the other phone's clipboard, with a notification that opens links. |
| **👊 Fist bump** | Pairs two phones. Tap *👊 Fist-bump pair* on both, then both people punch a fist toward their camera at the same moment, or **tap the two phones together**. If the bumps are within 1.5 s of each other, the phones pair. No code to compare. |

**🏖️ Trip mode** (switch under *My phones*): every new photo or video you take is sent automatically to all your paired phones, with no internet needed. Phones that are out of range get them the next time they're nearby. If items are waiting for another paired phone that's in range, Handsoff switches its one-at-a-time link over to that phone.

The fastest send needs no screen touches: open Handsoff → show 3 fingers → (show 2 fingers for phone #2) → open hand → fist → open hand.

## How it works

| | |
|---|---|
| **Connection** | [Google Nearby Connections](https://developers.google.com/nearby/connections/overview) (the technology behind Quick Share). The phones find each other over Bluetooth and transfer over Wi-Fi Direct, which is fast and needs no router. |
| **Pairing** | Once, either by **fist bump** (above) or like Bluetooth: tap **Pair** next to the phone under **Nearby** and check that both phones show the same 4-digit code. Paired phones are remembered permanently and reconnect on their own whenever they're near each other. |
| **Sending** | Pick photos, videos or files, or use **Gallery → Share → Handsoff**. Several at once is fine. Make a fist to grab the stack, then open your hand to send. You can also tap the stack, or press **Send**. |
| **Receiving** | Happens in the background, even when the app is closed. Received items are saved to **Gallery → Handsoff** (photos in `Pictures/Handsoff`, videos in `Movies/Handsoff`, other files in `Download/Handsoff`), and a notification says who sent what. |
| **Phone not nearby?** | Items wait on your phone and go automatically the next time the paired phone is in range. |
| **Gestures** | The front camera and Google's on-device MediaPipe hand model (`assets/hand_landmarker.task`). The grab/release logic ([GestureTracker.kt](app/src/main/java/com/handsoff/app/gesture/GestureTracker.kt)) is the same tested curl-score algorithm as the web version. |

### The code
```
app/src/main/java/com/handsoff/app/
  MainActivity.kt          permissions, photo/file pickers, Share → Handsoff
  HandsoffApp.kt           app-wide state (paired phones, selection)
  Notifier.kt              "ready" and "received" notifications
  data/DeviceStore.kt      this phone's id, paired phones, received history
  nearby/NearbyManager.kt  discovery, pairing, auto-reconnect, sending, receiving, offline queue
  nearby/Protocol.kt       messages between phones (unit-tested)
  nearby/TransferService   keeps receiving in the background
  gesture/GestureTracker   grab / release detection + finger counting (unit-tested)
  gesture/Holds.kt         "hold N fingers" timer and fist-bump detector (unit-tested)
  gesture/Motions.kt       👍 ✌️ 👌 signs, swipe, wave and flick detection (unit-tested)
  gesture/HandTracker      camera frames → MediaPipe → gestures
  storage/MediaSaver.kt    saves received items to the Gallery / Downloads
  ui/                      Jetpack Compose screen
```

## Build and install

You need Android Studio, or just the Android SDK and JDK 17, plus **two Android phones running Android 10 or newer**.

```bash
cd android
gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
gradlew testDebugUnitTest    # gesture, sign, motion, bump and protocol tests (19)
```

You can install it on each phone in either of two ways:
- **USB:** turn on *Developer options → USB debugging*, connect the phone, then run `gradlew installDebug` (or `adb install app/build/outputs/apk/debug/app-debug.apk`).
- **Without a cable:** copy `app-debug.apk` to the phone (WhatsApp it to yourself, use Drive, or a USB file copy), open it, and allow *Install unknown apps* when asked.

On an 8 GB laptop, close other apps before building, and don't use the emulator: Nearby needs real Bluetooth and Wi-Fi anyway.

## First use
1. Install the app on both phones and open it on both. Allow **Nearby devices**, **Location**, **Camera** and **Notifications** when asked.
2. Keep Bluetooth on (and Location on Android 10–12).
3. On one phone, tap **Pair** next to the other phone's name. Check the code matches on both phones and tap **Pair** on each.
4. Done. From now on: Gallery → select photos → **Share → Handsoff** → make a fist, then open your hand. The photos appear in the other phone's Gallery.

## How fist-bump pairing stays safe
- In bump mode, phones link up only with other phones that are also in bump mode, and only for 60 seconds.
- Until the bumps match, those temporary links can only exchange "bump" signals. Files from them are refused.
- Two bumps must land within 1.5 seconds of each other. A bump is measured on each phone's own clock when the signal arrives, so the phones' clocks don't need to agree.
- For extra certainty, use the code-pairing option instead.

## Limits
- **Android only.** iPhones can't use Nearby Connections with Android.
- **The phones must be near each other** (same room, roughly 10–30 m). For far-away transfers, use the web version in the repo root.
- **Gestures need the app open on screen.** Android doesn't allow apps to use the camera in the background. Receiving, though, works in the background.
