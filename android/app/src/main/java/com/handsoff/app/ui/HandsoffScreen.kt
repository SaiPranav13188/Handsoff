package com.handsoff.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.handsoff.app.data.PairedDevice
import com.handsoff.app.data.ReceivedEntry
import com.handsoff.app.gesture.BumpDetector
import com.handsoff.app.gesture.CountHold
import com.handsoff.app.gesture.Direction
import com.handsoff.app.gesture.FlickTracker
import com.handsoff.app.gesture.HandSign
import com.handsoff.app.gesture.SignHold
import com.handsoff.app.gesture.SwipeDetector
import com.handsoff.app.gesture.WaveDetector
import com.handsoff.app.gesture.GestureEvent
import com.handsoff.app.gesture.GestureFrame
import com.handsoff.app.gesture.HandState
import com.handsoff.app.nearby.BumpUi
import com.handsoff.app.nearby.HandsoffState
import com.handsoff.app.nearby.OutItem
import com.handsoff.app.nearby.Progress
import com.handsoff.app.nearby.ReactionUi
import com.handsoff.app.nearby.UnsendUi
import kotlinx.coroutines.delay

/** Most recipients you can choose by showing fingers (5 fingers = an open hand, used for grabbing). */
private const val MAX_FINGER_RECIPIENTS = 4

/** How long the "fly away" animation runs after a send. */
private const val FLY_MS = 700L

/** Flick directions for phones #1, #2, #3 when releasing. */
private val FLICK_ORDER = listOf(Direction.LEFT, Direction.RIGHT, Direction.UP)
private fun arrow(d: Direction) = when (d) {
    Direction.LEFT -> "←"
    Direction.RIGHT -> "→"
    Direction.UP -> "↑"
    Direction.DOWN -> "↓"
}

@Composable
fun HandsoffScreen(
    state: HandsoffState,
    items: List<OutItem>,
    missingPermissions: Boolean,
    cameraAllowed: Boolean,
    onGrantPermissions: () -> Unit,
    onPickMedia: () -> Unit,
    onPickFiles: () -> Unit,
    onPickRecent: (count: Int) -> Unit,
    onClearItems: () -> Unit,
    onSend: (deviceId: String) -> Unit,
    onPair: (deviceId: String) -> Unit,
    onUnpair: (deviceId: String) -> Unit,
    onAnswerPairing: (Boolean) -> Unit,
    onStartBump: () -> Unit,
    onStopBump: () -> Unit,
    onBump: () -> Unit,
    onNotify: (String) -> Unit,
    onMessageShown: (Long) -> Unit,
    onReact: (emoji: String) -> Unit,
    onDismissReaction: () -> Unit,
    onUnsend: () -> Unit,
    onDismissUnsend: () -> Unit,
    onSendClipboard: (deviceId: String) -> Unit,
    onTripMode: (Boolean) -> Unit,
    onBrowse: (step: Int) -> Unit,
    onRemoveItem: (OutItem) -> Unit,
    guideOpen: Boolean,
    onOpenGuide: () -> Unit,
    onCloseGuide: () -> Unit,
    onRemoveFirst: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it.text)
            onMessageShown(it.id)
        }
    }

    // Who to send to: the chosen phone, else a connected one, else any paired one.
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val target = state.paired.firstOrNull { it.id == chosen }
        ?: state.paired.firstOrNull { it.id in state.connected }
        ?: state.paired.firstOrNull()
    var unpairAsk by remember { mutableStateOf<PairedDevice?>(null) }

    // ── Gesture state ────────────────────────────────────────────────────────
    val counter = remember { CountHold(holdMs = 800) }
    // After fingers pick photos, the next fist grabs them even if the hand never
    // looked fully "open" (e.g. 2 fingers → fist).
    var grabArmed by remember { mutableStateOf(false) }
    val bumpDetector = remember { BumpDetector() }
    val signHold = remember { SignHold() }
    val swipe = remember { SwipeDetector() }
    val wave = remember { WaveDetector() }
    val flick = remember { FlickTracker() }
    var clock by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val reactOffer = state.reactTo?.takeIf { clock < it.until && items.isEmpty() }
    val unsendOffer = state.lastSent?.takeIf { clock < it.until }
    LaunchedEffect(state.lastSent, unsendOffer == null) { if (state.lastSent != null && unsendOffer == null) onDismissUnsend() }
    var hand by remember { mutableStateOf(HandState.NONE) }
    var fingers by remember { mutableIntStateOf(-1) }
    var holdProgress by remember { mutableFloatStateOf(0f) }
    var holding by remember { mutableStateOf(false) }
    var flying by remember { mutableStateOf(false) }
    LaunchedEffect(flying) {
        if (flying) {
            delay(FLY_MS)
            flying = false
        }
    }
    val bumping = state.bump != null
    val canSend = items.isNotEmpty() && target != null && !flying
    val fingerRecipients = minOf(MAX_FINGER_RECIPIENTS, state.paired.size)

    val sendTo = { device: PairedDevice? ->
        val to = device ?: target
        if (canSend && to != null) {
            holding = false
            flying = true
            onSend(to.id)
        }
    }
    val send = { sendTo(null) }

    /** What holding up [n] fingers would do right now, or null if nothing. */
    fun fingerAction(n: Int): String? = when {
        bumping || holding || flying || reactOffer != null -> null
        items.isEmpty() && n in 1..5 -> "Selecting your latest ${if (n == 1) "photo" else "$n photos"}"
        items.isNotEmpty() && state.paired.size > 1 && n in 1..fingerRecipients -> "Sending to #$n ${state.paired[n - 1].name}"
        else -> null
    }

    val onFrame: (GestureFrame) -> Unit = { f ->
        hand = f.state
        fingers = f.fingers
        val now = SystemClock.uptimeMillis()
        if (bumping) {
            if (bumpDetector.update(f.state == HandState.CLOSED, f.handSize, now)) onBump()
        } else {
            val sign = signHold.update(f.sign, now)
            val openPalm = f.palm.takeIf { f.state == HandState.OPEN }

            // 👍 / ✌️ right after receiving: react to the sender.
            if (reactOffer != null) {
                when (sign) {
                    HandSign.THUMBS_UP -> onReact("👍")
                    HandSign.PEACE -> onReact("✌️")
                    else -> Unit
                }
            }
            // 👋 Wave within a few seconds of sending: pull it back.
            if (unsendOffer != null && wave.update(openPalm, now)) onUnsend()
            // 👌 Pinch: remove the top item, or (nothing picked) send the clipboard.
            if (sign == HandSign.PINCH && !holding && !flying) {
                if (items.isNotEmpty()) onRemoveFirst() else target?.let { onSendClipboard(it.id) }
            }
            // Swipe sideways to flip through the picked items.
            if (items.size > 1 && !holding && !flying) {
                val step = swipe.update(openPalm, now)
                if (step != 0) onBrowse(step)
            }
            if (holding) flick.add(f.palm, now) else flick.clear()

            val counting = !holding && !flying && f.sign != HandSign.PINCH
            val picked = if (counting) counter.update(f.fingers, now) else null
            holdProgress = if (fingerAction(counter.count) != null) counter.progress else 0f
            if (picked != null && fingerAction(picked) != null) {
                if (items.isEmpty()) {
                    onPickRecent(picked)
                    grabArmed = true
                } else {
                    val device = state.paired[picked - 1]
                    chosen = device.id
                    onNotify("Sending to ${device.name}")
                }
            }
            // Fingers → fist right after picking: that fist is the grab.
            if (grabArmed && items.isNotEmpty() && canSend && !holding && f.state == HandState.CLOSED && f.fingers == 0) {
                holding = true
                grabArmed = false
            }
            when (f.event) {
                GestureEvent.GRAB -> if (canSend) {
                    holding = true
                    grabArmed = false
                }
                GestureEvent.RELEASE -> if (holding) {
                    // A flick while letting go picks the phone in that direction.
                    val dir = if (state.paired.size > 1) flick.direction() else null
                    val byFlick = dir?.let { d -> state.paired.getOrNull(FLICK_ORDER.indexOf(d)) }
                    if (byFlick != null) onNotify("${arrow(dir)} Flicked to ${byFlick.name}")
                    sendTo(byFlick)
                }
                null -> if (holding && f.state == HandState.NONE) holding = false
            }
        }
    }

    // Phones tapped together count as a bump too.
    if (bumping) KnockListener(onKnock = onBump)

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = Color(0xFF0B0D12)) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item { Header(state, onOpenGuide) }

            if (missingPermissions) {
                item {
                    Notice(
                        "Handsoff needs Nearby devices, Location and Camera to find phones and see your hand.",
                        "Allow",
                        onGrantPermissions,
                    )
                }
            } else if (state.problem != null) {
                item { Notice(state.problem, null, null) }
            }

            item {
                Section(
                    "My phones",
                    action = if (bumping) null else "👊 Fist-bump pair",
                    onAction = onStartBump,
                ) {
                    if (state.paired.isEmpty()) {
                        Text(
                            "No paired phones yet. Open Handsoff on the other phone, then tap 👊 Fist-bump pair on both — or use Pair under Nearby.",
                            color = Muted,
                            fontSize = 14.sp,
                        )
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(state.paired, key = { _, d -> d.id }) { i, d ->
                                PhoneChip(
                                    number = (i + 1).takeIf { it <= MAX_FINGER_RECIPIENTS && state.paired.size > 1 },
                                    flick = FLICK_ORDER.getOrNull(i)?.takeIf { state.paired.size > 1 }?.let(::arrow),
                                    device = d,
                                    online = d.id in state.connected,
                                    selected = d.id == target?.id,
                                    queued = state.queued[d.id] ?: 0,
                                    onClick = { chosen = d.id },
                                    onLongClick = { unpairAsk = d },
                                )
                            }
                        }
                    }
                }
            }

            state.bump?.let { b -> item { BumpCard(b, cameraAllowed, onStopBump) } }

            reactOffer?.let { r -> item { ReactionCard(r, cameraAllowed, onReact, onDismissReaction) } }
            unsendOffer?.let { u -> item { UnsendCard(u, clock, cameraAllowed, onUnsend) } }

            if (state.paired.isNotEmpty() && !bumping) {
                item { TripCard(state.tripMode, state.tripShared, state.paired.size, onTripMode) }
            }

            if (!bumping && (state.nearby.isNotEmpty() || state.paired.isEmpty())) {
                item {
                    Section("Nearby") {
                        if (state.nearby.isEmpty()) {
                            Text("Looking for phones with Handsoff open…", color = Muted, fontSize = 14.sp)
                        }
                        state.nearby.forEach { peer ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(peer.name, Modifier.weight(1f), fontSize = 16.sp)
                                Button(onClick = { onPair(peer.deviceId) }) { Text("Pair") }
                            }
                        }
                    }
                }
            }

            if (cameraAllowed) {
                item {
                    GesturePanel(
                        onFrame = onFrame,
                        hand = hand,
                        fingers = fingers,
                        action = fingerAction(fingers),
                        holdProgress = holdProgress,
                        hint = when {
                            bumping -> "Punch your fist toward the camera at the same moment as the other person — or tap the phones together"
                            reactOffer != null -> "Show 👍 or ✌️ to react to ${reactOffer.from}"
                            unsendOffer != null -> "👋 Wave to unsend"
                            holding && state.paired.size > 1 -> "Open your hand to send — flick ← → ↑ to pick the phone"
                            holding -> "Open your hand to send to ${target?.name}"
                            items.isEmpty() -> "Show 1–5 fingers to select your latest photos, then make a fist and open your hand to send"
                            target == null -> "Pair a phone first"
                            state.paired.size > 1 -> "Make a fist to grab, open to send · or show 1–$fingerRecipients fingers to change the phone"
                            else -> "Make a fist to grab · open your hand to send"
                        },
                    )
                }
            }

            item {
                SendArea(
                    items = items,
                    target = target,
                    targetOnline = target?.id in state.connected,
                    holding = holding,
                    flying = flying,
                    canSend = canSend,
                    sending = state.sending,
                    onTapStack = { if (holding) send() else if (canSend) holding = true },
                    onPickMedia = onPickMedia,
                    onPickFiles = onPickFiles,
                    onClear = onClearItems,
                    onSend = send,
                    onClipboard = { target?.let { onSendClipboard(it.id) } },
                    onRemoveItem = onRemoveItem,
                )
            }

            state.receiving?.let { p -> item { ProgressRow(p.label, p.done, p.total) } }

            if (state.received.isNotEmpty()) {
                item { Section("Received") { ReceivedGrid(state.received.take(12)) } }
            }
        }
    }

    if (guideOpen) GuideScreen(onClose = onCloseGuide)

    state.prompt?.let { p ->
        AlertDialog(
            onDismissRequest = { onAnswerPairing(false) },
            title = { Text("Pair with ${p.name}?") },
            text = { Text("Check that ${p.name} shows the same code:\n\n${p.code}\n\nYou only need to do this once.") },
            confirmButton = { Button(onClick = { onAnswerPairing(true) }) { Text("Pair") } },
            dismissButton = { TextButton(onClick = { onAnswerPairing(false) }) { Text("Cancel") } },
        )
    }
    unpairAsk?.let { d ->
        AlertDialog(
            onDismissRequest = { unpairAsk = null },
            title = { Text("Remove ${d.name}?") },
            text = { Text("You'll need to pair again to send to it.") },
            confirmButton = { TextButton(onClick = { onUnpair(d.id); unpairAsk = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { unpairAsk = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun Header(state: HandsoffState, onOpenGuide: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).background(Accent.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("✋", fontSize = 18.sp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Handsoff", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            Text("This phone: ${state.myName}", color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val (dot, label) = when {
            !state.running -> Muted to "Off"
            state.connected.isNotEmpty() -> Accent to "Connected"
            else -> Warn to "Ready"
        }
        Row(
            Modifier.border(1.dp, Color(0x22FFFFFF), CircleShape).padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 13.sp)
        }
        Spacer(Modifier.width(8.dp))
        // Opens the "How to use" guide.
        Box(
            Modifier.size(34.dp).border(1.dp, Color(0x22FFFFFF), CircleShape).clickable(onClick = onOpenGuide),
            contentAlignment = Alignment.Center,
        ) { Text("?", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun Notice(text: String, action: String?, onAction: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().background(Warn.copy(alpha = 0.12f), RoundedCornerShape(14.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), fontSize = 14.sp)
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun Section(title: String, action: String? = null, onAction: () -> Unit = {}, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title.uppercase(),
                Modifier.weight(1f),
                color = Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
            if (action != null) TextButton(onClick = onAction) { Text(action, fontSize = 13.sp) }
        }
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhoneChip(
    number: Int?,
    flick: String?,
    device: PairedDevice,
    online: Boolean,
    selected: Boolean,
    queued: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier
            .border(1.5.dp, if (selected) Accent else Color(0x22FFFFFF), CircleShape)
            .background(if (selected) Accent.copy(alpha = 0.1f) else Color.Transparent, CircleShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            // The number of fingers that selects this phone.
            Box(
                Modifier.size(22.dp).background(if (selected) Accent else Color(0x33FFFFFF), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("$number", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (selected) Color(0xFF062925) else Color.White)
            }
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.size(8.dp).background(if (online) Accent else Muted, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(device.name, fontSize = 15.sp, maxLines = 1)
        if (flick != null) Text("  $flick", color = Muted, fontSize = 14.sp)
        if (queued > 0) Text("  · $queued waiting", color = Warn, fontSize = 12.sp)
    }
}

@Composable
private fun BumpCard(bump: BumpUi, cameraAllowed: Boolean, onCancel: () -> Unit) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(bump.endsAt) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val secondsLeft = ((bump.endsAt - now) / 1000).coerceAtLeast(0)
    Column(
        Modifier.fillMaxWidth().background(Accent.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
            .border(1.dp, Accent.copy(alpha = 0.4f), RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("👊 Fist-bump pairing", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text("${secondsLeft}s", color = Muted, fontSize = 13.sp)
        }
        Text(
            if (bump.ready.isEmpty()) "Waiting for the other phone… tap 👊 Fist-bump pair on it too."
            else "Ready with ${bump.ready.joinToString()}. Now bump — both at the same moment!",
            fontSize = 14.sp,
        )
        Text(
            (if (cameraAllowed) "Punch your fist toward your camera, or " else "") + "tap the two phones together.",
            color = Muted,
            fontSize = 13.sp,
        )
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun ReactionCard(r: ReactionUi, cameraAllowed: Boolean, onReact: (String) -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Got ${if (r.count == 1) "1 item" else "${r.count} items"} from ${r.from}", fontSize = 14.sp)
            Text(if (cameraAllowed) "Show 👍 or ✌️ to react" else "React:", color = Muted, fontSize = 12.sp)
        }
        TextButton(onClick = { onReact("👍") }) { Text("👍", fontSize = 20.sp) }
        TextButton(onClick = { onReact("✌️") }) { Text("✌️", fontSize = 20.sp) }
        TextButton(onClick = onDismiss) { Text("✕", color = Muted) }
    }
}

@Composable
private fun UnsendCard(u: UnsendUi, clock: Long, cameraAllowed: Boolean, onUnsend: () -> Unit) {
    val left = ((u.until - clock + 999) / 1000).coerceAtLeast(0)
    Row(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Sent to ${u.to}" + (if (cameraAllowed) " · 👋 wave to unsend" else "") + " (${left}s)",
            Modifier.weight(1f),
            fontSize = 14.sp,
        )
        TextButton(onClick = onUnsend) { Text("Undo") }
    }
}

@Composable
private fun TripCard(on: Boolean, shared: Int, phones: Int, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (on) Accent.copy(alpha = 0.1f) else Panel, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("🏖️ Trip mode", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                if (on) "New photos you take go to ${if (phones == 1) "your paired phone" else "all $phones paired phones"} automatically · $shared shared"
                else "Auto-share every new photo with your paired phones — no internet needed",
                color = Muted,
                fontSize = 12.sp,
            )
        }
        Switch(checked = on, onCheckedChange = onToggle)
    }
}

@Composable
private fun GesturePanel(
    onFrame: (GestureFrame) -> Unit,
    hand: HandState,
    fingers: Int,
    action: String?,
    holdProgress: Float,
    hint: String,
) {
    Row(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(20.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GestureCamera(onFrame = onFrame, modifier = Modifier.width(112.dp).height(84.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when {
                    hand == HandState.NONE -> "Show your hand"
                    hand == HandState.CLOSED -> "✊ Fist"
                    fingers in 1..4 -> "☝ $fingers finger${if (fingers > 1) "s" else ""}"
                    else -> "✋ Open hand"
                },
                color = when (hand) {
                    HandState.OPEN -> Accent
                    HandState.CLOSED -> Warn
                    HandState.NONE -> Muted
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (action != null && holdProgress > 0f) {
                Text("$action…", fontSize = 13.sp)
                LinearProgressIndicator(progress = { holdProgress }, modifier = Modifier.fillMaxWidth(), color = Accent)
            } else {
                Text(hint, color = Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SendArea(
    items: List<OutItem>,
    target: PairedDevice?,
    targetOnline: Boolean,
    holding: Boolean,
    flying: Boolean,
    canSend: Boolean,
    sending: Progress?,
    onTapStack: () -> Unit,
    onPickMedia: () -> Unit,
    onPickFiles: () -> Unit,
    onClear: () -> Unit,
    onSend: () -> Unit,
    onClipboard: () -> Unit,
    onRemoveItem: (OutItem) -> Unit,
) {
    val scale by animateFloatAsState(if (holding) 0.82f else 1f, label = "hold")
    val lift by animateFloatAsState(
        targetValue = if (flying) -1400f else 0f,
        animationSpec = tween(if (flying) FLY_MS.toInt() - 50 else 0),
        label = "fly",
    )

    Column(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (items.isEmpty()) {
            Text("Send photos, videos or files", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(
                "Show fingers to the camera for your latest photos, pick them here, or use Share → Handsoff in your Gallery.",
                color = Muted,
                fontSize = 14.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onPickMedia) { Text("Photos & videos") }
                OutlinedButton(onClick = onPickFiles) { Text("Files") }
            }
            if (target != null) TextButton(onClick = onClipboard) { Text("📋 Send my clipboard to ${target.name}") }
            return@Column
        }

        // The stack of picked items: it shrinks while "held" and flies off when sent.
        Box(
            Modifier
                .fillMaxWidth(0.7f)
                .aspectRatio(4f / 3f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationY = lift
                    alpha = if (flying) 0.85f else 1f
                }
                .clickable(onClick = onTapStack),
            contentAlignment = Alignment.Center,
        ) {
            items.take(3).reversed().forEachIndexed { idx, item ->
                val depth = minOf(items.size, 3) - 1 - idx
                Thumb(
                    item.uri,
                    item.name,
                    item.mime,
                    Modifier
                        .fillMaxSize()
                        .rotate(listOf(0f, -5f, 6f)[depth])
                        .offset(x = (depth * 8).dp, y = (depth * 6).dp)
                        .graphicsLayer { alpha = 1f - depth * 0.25f }
                        .border(
                            if (holding && depth == 0) 2.dp else 0.dp,
                            if (holding && depth == 0) Warn else Color.Transparent,
                            RoundedCornerShape(14.dp),
                        ),
                )
            }
            if (items.size > 1) {
                Text(
                    "${items.size} items",
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 10.dp, y = (-10).dp)
                        .background(Accent, CircleShape)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    color = Color(0xFF062925),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // Every selected item, each with its own ✕ to drop just that one.
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(items, key = { _, it -> it.uri.toString() }) { _, item ->
                Box(Modifier.size(68.dp)) {
                    Thumb(item.uri, item.name, item.mime, Modifier.fillMaxSize())
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(3.dp)
                            .size(24.dp)
                            .background(Color(0xDD0B0D12), CircleShape)
                            .clickable { onRemoveItem(item) },
                        contentAlignment = Alignment.Center,
                    ) { Text("✕", fontSize = 12.sp, color = Color.White) }
                }
            }
        }
        Text(
            "${if (items.size == 1) "1 item" else "${items.size} items"} selected · tap ✕ to remove one" +
                if (items.size > 1) " · swipe ↔ in the air to browse" else "",
            color = Muted,
            fontSize = 12.sp,
        )
        when {
            target == null -> Text("Pair a phone first (👊 Fist-bump pair above)", color = Muted, fontSize = 14.sp)
            flying -> Text("Sending to ${target.name}…", color = Muted, fontSize = 14.sp)
            !targetOnline -> Text("${target.name} isn't nearby — items will wait and send when it is.", color = Warn, fontSize = 13.sp)
            else -> Text("To: ${target.name} · tap the stack twice to send without gestures", color = Muted, fontSize = 13.sp)
        }

        sending?.let { ProgressRow(it.label, it.done, it.total) }

        // The Send button takes the spare width; a long phone name is cut with "…".
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onSend, enabled = canSend, modifier = Modifier.weight(1f)) {
                Text(if (target != null) "Send to ${target.name}" else "Send", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = onPickMedia) { Text("Add", maxLines = 1) }
            TextButton(onClick = onClear) { Text("Clear", maxLines = 1) }
        }
    }
}

@Composable
private fun ProgressRow(label: String, done: Long, total: Long) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 13.sp, color = Muted)
        LinearProgressIndicator(
            progress = { if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth(),
            color = Accent,
        )
    }
}

@Composable
private fun ReceivedGrid(entries: List<ReceivedEntry>) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { e ->
                    val uri = Uri.parse(e.uri)
                    Thumb(
                        uri,
                        e.name,
                        e.mime,
                        Modifier.weight(1f).aspectRatio(1f).clickable {
                            val open = Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, e.mime)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            try {
                                context.startActivity(open)
                            } catch (ex: ActivityNotFoundException) {
                                /* no app for this type */
                            }
                        },
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Text(
            "Saved in Gallery → Handsoff (files in Downloads/Handsoff). Latest from: ${entries.first().from}",
            color = Muted,
            fontSize = 12.sp,
        )
    }
}
