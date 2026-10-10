package com.handsoff.app.nearby

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.provider.MediaStore
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.handsoff.app.Notifier
import com.handsoff.app.data.DeviceStore
import com.handsoff.app.data.PairedDevice
import com.handsoff.app.data.ReceivedEntry
import com.handsoff.app.storage.MediaSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Something picked to send: a content URI plus what we know about it. */
data class OutItem(val uri: Uri, val name: String, val mime: String, val size: Long)

data class NearbyPeer(val deviceId: String, val name: String)

data class PairingPrompt(val endpointId: String, val deviceId: String, val name: String, val code: String)

data class Progress(val label: String, val done: Long, val total: Long)

data class UiMessage(val id: Long, val text: String)

/** The latest batch this phone received, which can still get a 👍 / ✌️ back until [until] (elapsedRealtime). */
data class ReactionUi(val batchId: String, val deviceId: String, val from: String, val count: Int, val until: Long)

/** The latest batch this phone sent, which a wave can still pull back until [until] (elapsedRealtime). */
data class UnsendUi(val batchId: String, val to: String, val until: Long)

/** Fist-bump pairing in progress: until [endsAt] (elapsedRealtime), with these phones ready to bump. */
data class BumpUi(val endsAt: Long, val ready: List<String>)

data class HandsoffState(
    val myName: String,
    val running: Boolean = false,
    val problem: String? = null,
    val paired: List<PairedDevice> = emptyList(),
    val connected: Set<String> = emptySet(),
    val nearby: List<NearbyPeer> = emptyList(),
    val prompt: PairingPrompt? = null,
    val sending: Progress? = null,
    val receiving: Progress? = null,
    val queued: Map<String, Int> = emptyMap(),
    val received: List<ReceivedEntry> = emptyList(),
    val message: UiMessage? = null,
    val bump: BumpUi? = null,
    val reactTo: ReactionUi? = null,
    val lastSent: UnsendUi? = null,
    val tripMode: Boolean = false,
    val tripShared: Int = 0,
)

/**
 * Phone-to-phone link over Google Nearby Connections (Bluetooth + Wi-Fi Direct,
 * no Wi-Fi network or internet needed).
 *
 * Every phone advertises and discovers at the same time. A phone seen for the
 * first time shows up under "Nearby"; pairing it needs the same 4-digit code
 * confirmed on both phones, like Bluetooth. Paired phones are remembered and
 * reconnect silently whenever they're in range, so sending never needs setup.
 */
class NearbyManager(private val context: Context, private val store: DeviceStore) {
    private val client = Nearby.getConnectionsClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private val myEndpointName = Protocol.endpointName(store.myId, store.myName)
    private val myBumpName = Protocol.endpointName(store.myId, store.myName, bump = true)

    private val _state = MutableStateFlow(
        HandsoffState(
            myName = store.myName,
            paired = store.paired(),
            received = store.received(),
            tripMode = store.tripMode,
            tripShared = store.tripShared,
        ),
    )
    val state: StateFlow<HandsoffState> = _state

    // Endpoint bookkeeping (all touched on the main thread: Nearby calls back there).
    private val peers = HashMap<String, NearbyPeer>() // endpointId → who it is
    private val connected = HashMap<String, String>() // endpointId → deviceId
    private val connecting = HashSet<String>()
    private val pairingWanted = HashSet<String>() // deviceIds the user asked to pair with
    private var nextMessageId = 1L

    // Fist-bump pairing: temporary links to unpaired phones that may only exchange bumps.
    private var bumpUntil = 0L
    private val bumpAccepted = HashSet<String>() // endpointIds accepted because of bump mode
    private val bumpCandidates = HashMap<String, NearbyPeer>() // connected, not yet paired
    private val remoteBumpAt = HashMap<String, Long>()
    private var localBumpAt = 0L
    private val bumpActive get() = SystemClock.elapsedRealtime() < bumpUntil
    private val bumpTimeout = Runnable { if (_state.value.bump != null) stopBump("No bump matched — try again, at the same moment") }

    fun start() {
        if (_state.value.running) return
        _state.update { it.copy(running = true, problem = null) }
        client.startAdvertising(myEndpointName, SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(STRATEGY).build())
            .addOnFailureListener { onStartFailure(it) }
        startDiscovery()
        restoreOutbox()
        main.removeCallbacks(rotation)
        main.postDelayed(rotation, ROTATE_MS)
        applyTrip()
    }

    fun stop() {
        main.removeCallbacks(rotation)
        stopTripWatcher()
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        peers.clear(); connected.clear(); connecting.clear()
        _state.update { it.copy(running = false, connected = emptySet(), nearby = emptyList(), sending = null, receiving = null) }
    }

    private fun startDiscovery() {
        client.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(STRATEGY).build())
            .addOnSuccessListener { _state.update { it.copy(problem = null) } }
            .addOnFailureListener { onStartFailure(it) }
    }

    /** Nearby only reports an endpoint once; restarting discovery finds phones that came back. */
    private fun refreshDiscovery() {
        client.stopDiscovery()
        main.postDelayed({ if (_state.value.running) startDiscovery() }, 1500)
    }

    private fun onStartFailure(e: Exception) {
        val code = (e as? ApiException)?.statusCode
        if (code == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING || code == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) return
        _state.update { it.copy(problem = "Turn on Bluetooth, Wi-Fi and Location, then reopen Handsoff") }
    }

    fun consumeMessage(id: Long) = _state.update { if (it.message?.id == id) it.copy(message = null) else it }

    private fun say(text: String) = _state.update { it.copy(message = UiMessage(nextMessageId++, text)) }

    /** Shows a short message in the app (used by the screen for gesture feedback). */
    fun notify(text: String) = say(text)

    private fun publishLinks() = _state.update {
        it.copy(
            paired = store.paired(),
            connected = connected.values.toSet(),
            nearby = peers.values.filter { p -> !store.isPaired(p.deviceId) }.distinctBy { p -> p.deviceId },
        )
    }

    private fun endpointOf(deviceId: String) = connected.entries.firstOrNull { it.value == deviceId }?.key

    private fun nameOf(deviceId: String) =
        store.paired().firstOrNull { it.id == deviceId }?.name ?: peers.values.firstOrNull { it.deviceId == deviceId }?.name ?: "phone"

    // ── Discovery & pairing ──────────────────────────────────────────────────

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val id = Protocol.parseEndpointName(info.endpointName) ?: return
            if (id.deviceId == store.myId) return
            val peer = NearbyPeer(id.deviceId, id.name)
            peers[endpointId] = peer
            if (store.isPaired(peer.deviceId)) {
                store.savePaired(PairedDevice(peer.deviceId, peer.name)) // keep the name fresh
                if (!bumpActive) autoConnect(endpointId, peer)
            } else if (bumpActive) {
                autoConnect(endpointId, peer)
            }
            publishLinks()
        }

        override fun onEndpointLost(endpointId: String) {
            if (endpointId !in connected) peers.remove(endpointId)
            publishLinks()
        }
    }

    /** Paired phones reconnect on their own. The lower id calls; the other waits, then calls too if needed. */
    private fun autoConnect(endpointId: String, peer: NearbyPeer, force: Boolean = false) {
        if (endpointOf(peer.deviceId) != null || endpointId in connecting || endpointId in bumpCandidates) return
        if (!force && store.myId > peer.deviceId) {
            main.postDelayed({
                if (peers[endpointId] != null && (store.isPaired(peer.deviceId) || bumpActive)) autoConnect(endpointId, peer, force = true)
            }, 4000)
            return
        }
        request(endpointId)
    }

    private fun request(endpointId: String) {
        connecting += endpointId
        val peer = peers[endpointId]
        // In fist-bump mode, mark the request so phones that aren't bump-pairing ignore it.
        val name = if (bumpActive && peer != null && !store.isPaired(peer.deviceId)) myBumpName else myEndpointName
        client.requestConnection(name, endpointId, lifecycle).addOnFailureListener { e ->
            connecting -= endpointId
            val code = (e as? ApiException)?.statusCode
            if (code != ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT && peers[endpointId]?.deviceId in pairingWanted) {
                say("Couldn't reach ${peers[endpointId]?.name} — keep both phones close and try again")
                pairingWanted -= peers[endpointId]?.deviceId.orEmpty()
            }
        }
    }

    /** User tapped "Pair" on a nearby phone. */
    fun pair(deviceId: String) {
        val endpointId = peers.entries.firstOrNull { it.value.deviceId == deviceId }?.key ?: return
        pairingWanted += deviceId
        request(endpointId)
    }

    /** Both phones see the same code; the user confirms it on each. */
    fun answerPairing(accept: Boolean) {
        val prompt = _state.value.prompt ?: return
        _state.update { it.copy(prompt = null) }
        if (accept) client.acceptConnection(prompt.endpointId, payloads)
        else {
            client.rejectConnection(prompt.endpointId)
            pairingWanted -= prompt.deviceId
        }
    }

    fun unpair(deviceId: String) {
        store.removePaired(deviceId)
        endpointOf(deviceId)?.let { client.disconnectFromEndpoint(it); connected.remove(it) }
        dropOutbox(deviceId)
        publishLinks()
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val id = Protocol.parseEndpointName(info.endpointName)
            if (id == null) {
                client.rejectConnection(endpointId)
                return
            }
            peers[endpointId] = NearbyPeer(id.deviceId, id.name)
            if (store.isPaired(id.deviceId)) {
                client.acceptConnection(endpointId, payloads)
            } else if (bumpActive) {
                // Both people are in fist-bump mode; the bump itself confirms the pairing.
                bumpAccepted += endpointId
                client.acceptConnection(endpointId, payloads)
            } else if (id.bump) {
                client.rejectConnection(endpointId) // someone else is bump-pairing; not us
            } else {
                _state.update { it.copy(prompt = PairingPrompt(endpointId, id.deviceId, id.name, info.authenticationDigits)) }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            connecting -= endpointId
            val peer = peers[endpointId] ?: return
            if (_state.value.prompt?.endpointId == endpointId) _state.update { it.copy(prompt = null) }
            if (result.status.isSuccess && endpointId in bumpAccepted && !store.isPaired(peer.deviceId)) {
                bumpCandidates[endpointId] = peer
                publishBump()
                return
            }
            bumpAccepted -= endpointId
            if (result.status.isSuccess) {
                if (!store.isPaired(peer.deviceId)) say("Paired with ${peer.name} — it reconnects by itself from now on")
                store.savePaired(PairedDevice(peer.deviceId, peer.name))
                pairingWanted -= peer.deviceId
                connected[endpointId] = peer.deviceId
                _state.update { it.copy(problem = null) }
                publishLinks()
                flushOutbox(peer.deviceId)
            } else if (peer.deviceId in pairingWanted) {
                pairingWanted -= peer.deviceId
                say("Pairing with ${peer.name} was cancelled")
            }
        }

        override fun onDisconnected(endpointId: String) {
            connected.remove(endpointId)
            bumpAccepted -= endpointId
            remoteBumpAt.remove(endpointId)
            if (bumpCandidates.remove(endpointId) != null) publishBump()
            peers.remove(endpointId)
            failTransfersOn(endpointId)
            publishLinks()
            refreshDiscovery()
        }
    }

    // ── Fist-bump pairing ───────────────────────────────────────────────────

    /** Both people tap "Fist-bump pair"; for a minute their phones link up and wait for a bump. */
    fun startBump() {
        bumpUntil = SystemClock.elapsedRealtime() + BUMP_MODE_MS
        localBumpAt = 0L
        // Point-to-point allows one link at a time: free it for the new phone.
        connected.keys.toList().forEach { client.disconnectFromEndpoint(it) }
        connected.clear()
        publishLinks()
        publishBump()
        peers.forEach { (endpointId, peer) -> if (!store.isPaired(peer.deviceId)) autoConnect(endpointId, peer) }
        main.removeCallbacks(bumpTimeout)
        main.postDelayed(bumpTimeout, BUMP_MODE_MS)
        refreshDiscovery()
    }

    fun stopBump(message: String? = null) {
        bumpUntil = 0L
        main.removeCallbacks(bumpTimeout)
        bumpCandidates.keys.toList().forEach { client.disconnectFromEndpoint(it) }
        bumpCandidates.clear()
        bumpAccepted.clear()
        remoteBumpAt.clear()
        publishBump()
        message?.let { say(it) }
        refreshDiscovery() // reconnects paired phones
    }

    /** This phone saw a fist bump (camera) or a knock (phones tapped together). */
    fun localBump() {
        if (!bumpActive) return
        val now = SystemClock.elapsedRealtime()
        localBumpAt = now
        bumpCandidates.keys.forEach { client.sendPayload(it, Payload.fromBytes(Protocol.encode(Protocol.Bump))) }
        remoteBumpAt.entries.firstOrNull { now - it.value <= BUMP_MATCH_MS }?.let { matchBump(it.key) }
        if (bumpCandidates.isEmpty()) say("Bump seen — waiting for the other phone to join")
    }

    private fun onRemoteBump(endpointId: String) {
        val now = SystemClock.elapsedRealtime()
        remoteBumpAt[endpointId] = now
        if (now - localBumpAt <= BUMP_MATCH_MS) matchBump(endpointId)
    }

    private fun matchBump(endpointId: String) {
        val peer = bumpCandidates.remove(endpointId) ?: return
        bumpAccepted -= endpointId
        store.savePaired(PairedDevice(peer.deviceId, peer.name))
        connected[endpointId] = peer.deviceId
        client.sendPayload(endpointId, Payload.fromBytes(Protocol.encode(Protocol.BumpOk)))
        // Leave bump mode but keep this new link.
        bumpUntil = 0L
        main.removeCallbacks(bumpTimeout)
        bumpCandidates.keys.toList().forEach { client.disconnectFromEndpoint(it) }
        bumpCandidates.clear()
        remoteBumpAt.clear()
        publishLinks()
        publishBump()
        say("👊 Paired with ${peer.name}")
        flushOutbox(peer.deviceId)
    }

    private fun publishBump() = _state.update {
        it.copy(bump = if (bumpActive) BumpUi(bumpUntil, bumpCandidates.values.map { p -> p.name }) else null)
    }

    // ── Sending ──────────────────────────────────────────────────────────────

    private class Outgoing(
        val batchId: String,
        val endpointId: String,
        val deviceId: String,
        val label: String,
        val sizes: Map<Long, Long>,
        val outboxDir: File?,
    ) {
        val sent = HashMap<Long, Long>()
        val done = HashSet<Long>()
    }

    private val outgoing = HashMap<String, Outgoing>()
    private val payloadToOutgoing = HashMap<Long, Outgoing>()

    /** Batches sent recently (batchId → endpointId), so a wave can still pull them back. */
    private val recentSent = HashMap<String, Pair<String, Long>>()

    /** Sends now if the phone is connected, otherwise keeps the items until it is. */
    fun send(deviceId: String, items: List<OutItem>, quiet: Boolean = false) {
        if (items.isEmpty()) return
        val endpointId = endpointOf(deviceId)
        if (endpointId != null) {
            val payloads = items.mapNotNull { item ->
                try {
                    context.contentResolver.openFileDescriptor(item.uri, "r")?.let { item to Payload.fromFile(it) }
                } catch (e: Exception) {
                    null
                }
            }
            if (payloads.isEmpty()) {
                say("Couldn't open those items")
                return
            }
            val batchId = startBatch(endpointId, deviceId, payloads, outboxDir = null)
            if (!quiet) offerUnsend(batchId, deviceId)
        } else {
            scope.launch {
                val dir = withContext(Dispatchers.IO) { writeOutbox(deviceId, items) }
                if (dir != null) {
                    if (!quiet) {
                        say("${nameOf(deviceId)} isn't nearby — sending as soon as it is")
                        offerUnsend(QUEUED + dir.name, deviceId)
                    }
                    publishQueue()
                } else if (!quiet) say("Couldn't keep those items for later")
            }
        }
    }

    private fun startBatch(endpointId: String, deviceId: String, payloads: List<Pair<OutItem, Payload>>, outboxDir: File?): String {
        val batchId = UUID.randomUUID().toString().take(12)
        val metas = payloads.map { (item, p) -> Protocol.ItemMeta(p.id, Protocol.cleanName(item.name), Protocol.cleanMime(item.mime), item.size) }
        val out = Outgoing(
            batchId,
            endpointId,
            deviceId,
            "Sending ${countLabel(payloads.size)} to ${nameOf(deviceId)}",
            metas.associate { it.payloadId to it.size },
            outboxDir,
        )
        outgoing[batchId] = out
        payloads.forEach { (_, p) -> payloadToOutgoing[p.id] = out }
        client.sendPayload(endpointId, Payload.fromBytes(Protocol.encode(Protocol.Batch(batchId, store.myName, metas))))
        payloads.forEach { (_, p) -> client.sendPayload(endpointId, p) }
        recentSent[batchId] = endpointId to SystemClock.elapsedRealtime()
        publishSending()
        return batchId
    }

    // ── Wave to unsend ───────────────────────────────────────────────────────

    private fun offerUnsend(batchId: String, deviceId: String) {
        _state.update { it.copy(lastSent = UnsendUi(batchId, nameOf(deviceId), SystemClock.elapsedRealtime() + UNSEND_MS)) }
    }

    fun clearUnsend() = _state.update { it.copy(lastSent = null) }

    /** Pulls back the last batch: cancels it mid-transfer, or asks the receiver to delete it. */
    fun unsendLast() {
        val ui = _state.value.lastSent ?: return
        _state.update { it.copy(lastSent = null) }
        if (ui.batchId.startsWith(QUEUED)) {
            File(outboxRoot, ui.batchId.removePrefix(QUEUED)).deleteRecursively()
            publishQueue()
            say("👋 Unsent — nothing reached ${ui.to}")
            return
        }
        outgoing[ui.batchId]?.let { out ->
            out.sizes.keys.forEach { client.cancelPayload(it) }
            finishOutgoing(out)
            publishSending()
        }
        val endpointId = recentSent[ui.batchId]?.first
        if (endpointId != null && endpointId in connected) {
            client.sendPayload(endpointId, Payload.fromBytes(Protocol.encode(Protocol.Unsend(ui.batchId))))
            say("👋 Unsent from ${ui.to}")
        } else {
            say("Couldn't reach ${ui.to} to unsend")
        }
    }

    private fun onUnsend(endpointId: String, batchId: String) {
        val from = nameOf(connected[endpointId].orEmpty())
        incoming[batchId]?.takeIf { it.endpointId == endpointId }?.let { b ->
            b.cancelled = true
            incoming.remove(batchId)
            b.batch.items.forEach {
                client.cancelPayload(it.payloadId)
                payloadToIncoming.remove(it.payloadId)
                filePayloads.remove(it.payloadId)
            }
            deleteSaved(b.saved.values.toList())
            publishReceiving()
            say("$from unsent their items")
            return
        }
        val done = recentReceived.remove(batchId)?.takeIf { it.first == endpointId } ?: return
        deleteSaved(done.second)
        if (_state.value.reactTo?.batchId == batchId) _state.update { it.copy(reactTo = null) }
        val text = "$from unsent ${countLabel(done.second.size)} — removed from your Gallery"
        say(text)
        Notifier.simple(context, "👋 Unsent", text)
    }

    private fun deleteSaved(entries: List<ReceivedEntry>) {
        if (entries.isEmpty()) return
        store.removeReceived(entries.map { it.uri }.toSet())
        _state.update { it.copy(received = store.received()) }
        scope.launch(Dispatchers.IO) {
            entries.forEach {
                try {
                    context.contentResolver.delete(Uri.parse(it.uri), null, null)
                } catch (e: Exception) {
                    /* already gone */
                }
            }
        }
    }

    // ── 👍 / ✌️ reactions ───────────────────────────────────────────────────

    fun clearReaction() = _state.update { it.copy(reactTo = null) }

    fun react(emoji: String) {
        val ui = _state.value.reactTo ?: return
        _state.update { it.copy(reactTo = null) }
        val endpointId = endpointOf(ui.deviceId)
        if (endpointId == null) {
            say("${ui.from} isn't connected right now")
            return
        }
        client.sendPayload(endpointId, Payload.fromBytes(Protocol.encode(Protocol.React(ui.batchId, emoji))))
        say("Sent $emoji to ${ui.from}")
    }

    private fun onReaction(endpointId: String, emoji: String) {
        val from = nameOf(connected[endpointId].orEmpty())
        say("$from reacted $emoji")
        Notifier.simple(context, "$from reacted $emoji", "to the items you sent")
    }

    // ── 📋 Clipboard teleport ────────────────────────────────────────────────

    fun sendClipboard(deviceId: String, text: String) {
        val endpointId = endpointOf(deviceId)
        if (endpointId == null) {
            say("${nameOf(deviceId)} isn't nearby — clipboard not sent")
            return
        }
        client.sendPayload(endpointId, Payload.fromBytes(Protocol.encode(Protocol.Clip(text.take(Protocol.MAX_CLIP)))))
        say("📋 Clipboard sent to ${nameOf(deviceId)}")
    }

    private fun onClip(endpointId: String, text: String) {
        val from = nameOf(connected[endpointId].orEmpty())
        try {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Handsoff", text))
        } catch (e: Exception) {
            /* clipboard unavailable */
        }
        val link = Regex("https?://\\S+").find(text)?.value
        val open = link?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }
        val preview = text.take(120)
        say("📋 $from: $preview — copied")
        Notifier.simple(context, "📋 $from sent you ${if (link != null) "a link" else "text"} (copied)", preview, open)
    }

    // ── 🏖️ Trip mode ─────────────────────────────────────────────────────────

    private var tripObserver: ContentObserver? = null
    private val tripCollections = listOf(
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
    )
    private val tripCheck = Runnable { checkTripPhotos() }

    fun setTripMode(on: Boolean) {
        store.tripMode = on
        if (on) {
            // Only photos taken from now on are shared.
            scope.launch {
                withContext(Dispatchers.IO) { tripCollections.forEach { store.setTripLastId(it.toString(), maxId(it)) } }
                store.tripShared = 0
                _state.update { it.copy(tripMode = true, tripShared = 0) }
                applyTrip()
                say("🏖️ Trip mode on — new photos go to all your paired phones")
            }
        } else {
            _state.update { it.copy(tripMode = false) }
            applyTrip()
        }
    }

    private fun applyTrip() {
        if (store.tripMode && _state.value.running) {
            if (tripObserver != null) return
            val observer = object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) {
                    // Camera apps write in steps; wait for things to settle.
                    main.removeCallbacks(tripCheck)
                    main.postDelayed(tripCheck, 2500)
                }
            }
            tripCollections.forEach { context.contentResolver.registerContentObserver(it, true, observer) }
            tripObserver = observer
        } else stopTripWatcher()
    }

    private fun stopTripWatcher() {
        tripObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        tripObserver = null
        main.removeCallbacks(tripCheck)
    }

    private fun maxId(collection: Uri): Long = try {
        context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), null, null, "${MediaStore.MediaColumns._ID} DESC")
            ?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
    } catch (e: Exception) {
        0L
    }

    private fun checkTripPhotos() {
        if (!store.tripMode) return
        scope.launch {
            val fresh = withContext(Dispatchers.IO) {
                tripCollections.flatMap { collection ->
                    val key = collection.toString()
                    val last = store.tripLastId(key)
                    val found = mutableListOf<OutItem>()
                    var maxSeen = last
                    try {
                        context.contentResolver.query(
                            collection,
                            arrayOf(
                                MediaStore.MediaColumns._ID,
                                MediaStore.MediaColumns.DISPLAY_NAME,
                                MediaStore.MediaColumns.MIME_TYPE,
                                MediaStore.MediaColumns.SIZE,
                            ),
                            "${MediaStore.MediaColumns._ID} > ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0 AND " +
                                "${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE ?",
                            arrayOf(last.toString(), "%Handsoff%"),
                            "${MediaStore.MediaColumns._ID} ASC",
                        )?.use { c ->
                            while (c.moveToNext()) {
                                val id = c.getLong(0)
                                maxSeen = maxOf(maxSeen, id)
                                found += OutItem(
                                    ContentUris.withAppendedId(collection, id),
                                    c.getString(1) ?: "photo.jpg",
                                    c.getString(2) ?: "image/jpeg",
                                    c.getLong(3),
                                )
                            }
                        }
                    } catch (e: SecurityException) {
                        return@withContext null
                    } catch (e: Exception) {
                        /* skip this collection */
                    }
                    store.setTripLastId(key, maxSeen)
                    found
                }
            }
            if (fresh == null) {
                say("Allow Photos & videos so Trip mode can share new photos")
                return@launch
            }
            if (fresh.isEmpty()) return@launch
            val phones = store.paired()
            if (phones.isEmpty()) return@launch
            phones.forEach { send(it.id, fresh, quiet = true) }
            store.tripShared = store.tripShared + fresh.size
            _state.update { it.copy(tripShared = store.tripShared) }
            say("🏖️ Sharing ${countLabel(fresh.size)} with ${if (phones.size == 1) phones[0].name else "${phones.size} phones"}")
        }
    }

    // ── Queue rotation ───────────────────────────────────────────────────────
    // Point-to-point links one phone at a time. If items wait for another paired
    // phone that's in range, switch to it once nothing is being transferred.

    private val rotation = object : Runnable {
        override fun run() {
            rotateForQueue()
            main.postDelayed(this, ROTATE_MS)
        }
    }

    private fun rotateForQueue() {
        val now = SystemClock.elapsedRealtime()
        recentSent.entries.removeAll { now - it.value.second > RECENT_MS }
        recentReceived.entries.removeAll { now - it.value.third > RECENT_MS }
        _state.value.reactTo?.let { if (now > it.until) clearReaction() }
        if (bumpActive || outgoing.isNotEmpty() || incoming.isNotEmpty()) return
        val waiting = outboxBatches().map { it.second.getString("to") }.toSet()
        if (waiting.isEmpty()) return
        val linked = connected.values.toSet()
        linked.filter { it in waiting }.forEach { flushOutbox(it) }
        if (linked.any { it in waiting }) return
        val next = peers.entries.firstOrNull { it.value.deviceId in waiting && store.isPaired(it.value.deviceId) } ?: return
        connected.keys.toList().forEach { client.disconnectFromEndpoint(it) }
        connected.clear()
        publishLinks()
        request(next.key)
    }

    private fun publishSending() {
        val first = outgoing.values.firstOrNull()
        _state.update {
            it.copy(
                sending = first?.let { o ->
                    Progress(o.label, outgoing.values.sumOf { b -> b.sent.values.sum() }, outgoing.values.sumOf { b -> b.sizes.values.sum() })
                },
            )
        }
    }

    private fun onOutgoingUpdate(out: Outgoing, update: PayloadTransferUpdate) {
        when (update.status) {
            PayloadTransferUpdate.Status.IN_PROGRESS -> out.sent[update.payloadId] = update.bytesTransferred
            PayloadTransferUpdate.Status.SUCCESS -> {
                out.sent[update.payloadId] = out.sizes[update.payloadId] ?: update.bytesTransferred
                out.done += update.payloadId
                if (out.done.size == out.sizes.size) {
                    finishOutgoing(out)
                    say("Sent ${countLabel(out.sizes.size)}")
                    out.outboxDir?.deleteRecursively()
                    publishQueue()
                }
            }
            else -> {
                finishOutgoing(out)
                say("Sending failed — ${if (out.outboxDir != null) "will retry when the phone is back" else "try again"}")
            }
        }
        publishSending()
    }

    private fun finishOutgoing(out: Outgoing) {
        outgoing.remove(out.batchId)
        out.sizes.keys.forEach { payloadToOutgoing.remove(it) }
    }

    // ── Offline queue (files copied into app storage until the phone is back) ──

    private val outboxRoot get() = File(context.filesDir, "outbox")

    private fun writeOutbox(deviceId: String, items: List<OutItem>): File? = try {
        val dir = File(outboxRoot, "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6)}").apply { mkdirs() }
        val list = JSONArray()
        items.forEachIndexed { i, item ->
            val file = File(dir, "item-$i")
            context.contentResolver.openInputStream(item.uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            list.put(JSONObject().put("file", file.name).put("name", item.name).put("mime", item.mime).put("size", file.length()))
        }
        File(dir, "manifest.json").writeText(JSONObject().put("to", deviceId).put("items", list).toString())
        dir
    } catch (e: Exception) {
        null
    }

    private fun outboxBatches(): List<Pair<File, JSONObject>> =
        (outboxRoot.listFiles() ?: emptyArray()).sortedBy { it.name }.mapNotNull { dir ->
            try {
                dir to JSONObject(File(dir, "manifest.json").readText())
            } catch (e: Exception) {
                dir.deleteRecursively()
                null
            }
        }

    private fun restoreOutbox() = publishQueue()

    private fun publishQueue() {
        val counts = outboxBatches().groupingBy { it.second.getString("to") }.eachCount()
        _state.update { it.copy(queued = counts) }
    }

    private fun flushOutbox(deviceId: String) {
        val endpointId = endpointOf(deviceId) ?: return
        val busy = outgoing.values.mapNotNull { it.outboxDir }.toSet()
        for ((dir, manifest) in outboxBatches()) {
            if (manifest.getString("to") != deviceId || dir in busy) continue
            val arr = manifest.getJSONArray("items")
            val payloads = (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val file = File(dir, o.getString("file"))
                try {
                    OutItem(Uri.fromFile(file), o.getString("name"), o.getString("mime"), file.length()) to Payload.fromFile(file)
                } catch (e: Exception) {
                    null // file went missing
                }
            }
            if (payloads.isEmpty()) dir.deleteRecursively() else startBatch(endpointId, deviceId, payloads, dir)
        }
    }

    private fun dropOutbox(deviceId: String) {
        outboxBatches().filter { it.second.getString("to") == deviceId }.forEach { it.first.deleteRecursively() }
        publishQueue()
    }

    // ── Receiving ────────────────────────────────────────────────────────────

    private class IncomingBatch(val batch: Protocol.Batch, val endpointId: String) {
        var cancelled = false
        val bytes = HashMap<Long, Long>()
        val saved = HashMap<Long, ReceivedEntry>()
        var failed = 0
        val total = batch.items.sumOf { it.size }
    }

    private val incoming = HashMap<String, IncomingBatch>()
    private val payloadToIncoming = HashMap<Long, IncomingBatch>()
    private val filePayloads = HashMap<Long, Payload>()
    private val finishedEarly = HashSet<Long>() // file finished before its manifest arrived
    /** Completed batches (batchId → endpoint, items, time), so the sender can still unsend them. */
    private val recentReceived = HashMap<String, Triple<String, List<ReceivedEntry>, Long>>()

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (endpointId in bumpCandidates) {
                // Not paired yet: only bump signals are allowed.
                if (payload.type == Payload.Type.BYTES) {
                    when (Protocol.decode(payload.asBytes() ?: return)) {
                        Protocol.Bump -> onRemoteBump(endpointId)
                        Protocol.BumpOk -> if (SystemClock.elapsedRealtime() - localBumpAt < 3000) matchBump(endpointId)
                        else -> Unit
                    }
                } else client.cancelPayload(payload.id)
                return
            }
            if (endpointId !in connected) {
                if (payload.type != Payload.Type.BYTES) client.cancelPayload(payload.id)
                return
            }
            when (payload.type) {
                Payload.Type.BYTES -> when (val msg = Protocol.decode(payload.asBytes() ?: return)) {
                    is Protocol.Batch -> {
                        val b = IncomingBatch(msg, endpointId)
                        incoming[msg.id] = b
                        msg.items.forEach { payloadToIncoming[it.payloadId] = b }
                        publishReceiving()
                        msg.items.map { it.payloadId }.filter { it in finishedEarly }.forEach {
                            finishedEarly -= it
                            saveFile(b, it)
                        }
                    }
                    is Protocol.Ack -> say("Delivered ✓")
                    is Protocol.React -> onReaction(endpointId, msg.emoji)
                    is Protocol.Unsend -> onUnsend(endpointId, msg.id)
                    is Protocol.Clip -> onClip(endpointId, msg.text)
                    Protocol.Bump, Protocol.BumpOk, null -> Unit
                }
                Payload.Type.FILE -> filePayloads[payload.id] = payload
                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            payloadToOutgoing[update.payloadId]?.let { onOutgoingUpdate(it, update); return }
            if (update.payloadId !in filePayloads) return
            val b = payloadToIncoming[update.payloadId]
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> if (b != null) {
                    b.bytes[update.payloadId] = update.bytesTransferred
                    publishReceiving()
                }
                PayloadTransferUpdate.Status.SUCCESS -> if (b != null) saveFile(b, update.payloadId) else finishedEarly += update.payloadId
                else -> {
                    filePayloads.remove(update.payloadId)
                    if (b != null) {
                        b.failed++
                        maybeFinish(b)
                    }
                }
            }
        }
    }

    private fun saveFile(b: IncomingBatch, payloadId: Long) {
        val payload = filePayloads.remove(payloadId) ?: return
        val meta = b.batch.items.first { it.payloadId == payloadId }
        b.bytes[payloadId] = meta.size
        publishReceiving()
        scope.launch {
            val entry = withContext(Dispatchers.IO) {
                try {
                    val source = payload.asFile()?.asUri() ?: return@withContext null
                    val saved = context.contentResolver.openInputStream(source)!!.use { input ->
                        MediaSaver.save(context, input, meta.name, meta.mime)
                    }
                    try {
                        context.contentResolver.delete(source, null, null) // Nearby's temporary copy
                    } catch (e: Exception) {
                        /* best effort */
                    }
                    ReceivedEntry(saved.toString(), meta.name, meta.mime, b.batch.from.ifBlank { "A paired phone" }, System.currentTimeMillis())
                } catch (e: Exception) {
                    null
                }
            }
            if (b.cancelled) {
                entry?.let { deleteSaved(listOf(it)) } // unsent while this file was being saved
                return@launch
            }
            if (entry != null) b.saved[payloadId] = entry else b.failed++
            maybeFinish(b)
        }
    }

    private fun maybeFinish(b: IncomingBatch) {
        if (b.saved.size + b.failed < b.batch.items.size) return
        incoming.remove(b.batch.id)
        b.batch.items.forEach { payloadToIncoming.remove(it.payloadId) }
        val entries = b.batch.items.mapNotNull { b.saved[it.payloadId] }
        if (entries.isNotEmpty()) {
            store.addReceived(entries)
            _state.update { it.copy(received = store.received()) }
            Notifier.received(context, entries.first().from, entries)
            client.sendPayload(b.endpointId, Payload.fromBytes(Protocol.encode(Protocol.Ack(b.batch.id))))
            val now = SystemClock.elapsedRealtime()
            recentReceived[b.batch.id] = Triple(b.endpointId, entries, now)
            connected[b.endpointId]?.let { deviceId ->
                _state.update { it.copy(reactTo = ReactionUi(b.batch.id, deviceId, entries.first().from, entries.size, now + REACT_MS)) }
            }
        }
        if (b.failed > 0) say("${b.failed} item${if (b.failed > 1) "s" else ""} couldn't be received")
        publishReceiving()
    }

    private fun publishReceiving() {
        val first = incoming.values.firstOrNull()
        _state.update {
            it.copy(
                receiving = first?.let { b ->
                    Progress(
                        "Receiving ${countLabel(b.batch.items.size)} from ${b.batch.from.ifBlank { "a paired phone" }}",
                        incoming.values.sumOf { x -> x.bytes.values.sum() },
                        incoming.values.sumOf { x -> x.total },
                    )
                },
            )
        }
    }

    private fun failTransfersOn(endpointId: String) {
        outgoing.values.filter { it.endpointId == endpointId }.forEach {
            finishOutgoing(it)
            say(if (it.outboxDir != null) "Connection lost — will retry when the phone is back" else "Connection lost while sending")
        }
        incoming.values.filter { it.endpointId == endpointId }.forEach { b ->
            incoming.remove(b.batch.id)
            b.batch.items.forEach { payloadToIncoming.remove(it.payloadId); filePayloads.remove(it.payloadId) }
        }
        publishSending()
        publishReceiving()
    }

    companion object {
        private const val SERVICE_ID = "com.handsoff.app.transfer"
        // Point-to-point upgrades to Wi-Fi Direct for fast photo/video transfer between two phones.
        private val STRATEGY = Strategy.P2P_POINT_TO_POINT
        private const val BUMP_MODE_MS = 60_000L
        // Two bumps this close together count as the same fist bump.
        private const val BUMP_MATCH_MS = 1_500L
        private const val UNSEND_MS = 5_000L // how long the "wave to unsend" offer shows
        private const val REACT_MS = 120_000L // how long a received batch can get a reaction
        private const val RECENT_MS = 10 * 60_000L
        private const val ROTATE_MS = 8_000L
        private const val QUEUED = "queued:"

        fun countLabel(n: Int) = if (n == 1) "1 item" else "$n items"
    }
}
