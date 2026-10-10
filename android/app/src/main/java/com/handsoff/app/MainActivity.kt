package com.handsoff.app

import android.Manifest
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.handsoff.app.nearby.OutItem
import com.handsoff.app.nearby.TransferService
import com.handsoff.app.ui.HandsoffScreen
import com.handsoff.app.ui.HandsoffTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val app get() = HandsoffApp.from(this)

    private var missingPermissions by mutableStateOf(false)
    private var cameraAllowed by mutableStateOf(false)
    private var guideOpen by mutableStateOf(false)

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionsChanged() }

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { addUris(it) }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { addUris(it) }

    private val mediaPermissions: Array<String>
        get() = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private var afterMediaAccess: (() -> Unit)? = null

    private val askMedia = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val next = afterMediaAccess
        afterMediaAccess = null
        if (hasMediaAccess()) next?.invoke()
        else app.manager.notify("Allow Photos & videos for this — Handsoff only reads your newest photos")
    }

    private fun withMediaAccess(action: () -> Unit) {
        if (hasMediaAccess()) action() else {
            afterMediaAccess = action
            askMedia.launch(mediaPermissions)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShare(intent)
        guideOpen = !app.store.guideSeen

        setContent {
            HandsoffTheme {
                val state by app.manager.state.collectAsStateWithLifecycle()
                HandsoffScreen(
                    state = state,
                    items = app.selection,
                    missingPermissions = missingPermissions,
                    cameraAllowed = cameraAllowed,
                    onGrantPermissions = { askPermissions.launch(requiredPermissions) },
                    onPickMedia = {
                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    },
                    onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
                    onPickRecent = { n -> withMediaAccess { pickRecent(n) } },
                    onClearItems = { app.selection.clear() },
                    onSend = { deviceId ->
                        app.manager.send(deviceId, app.selection.toList())
                        // Let the "fly away" animation play before the stack empties.
                        lifecycleScope.launch {
                            delay(700)
                            app.selection.clear()
                        }
                    },
                    onPair = { app.manager.pair(it) },
                    onUnpair = { app.manager.unpair(it) },
                    onAnswerPairing = { app.manager.answerPairing(it) },
                    onStartBump = { app.manager.startBump() },
                    onStopBump = { app.manager.stopBump() },
                    onBump = { app.manager.localBump() },
                    onNotify = { app.manager.notify(it) },
                    onReact = { app.manager.react(it) },
                    onDismissReaction = { app.manager.clearReaction() },
                    onUnsend = { app.manager.unsendLast() },
                    onDismissUnsend = { app.manager.clearUnsend() },
                    onSendClipboard = { deviceId -> sendClipboard(deviceId) },
                    onTripMode = { on -> if (on) withMediaAccess { app.manager.setTripMode(true) } else app.manager.setTripMode(false) },
                    onBrowse = { step -> browse(step) },
                    onRemoveItem = { item -> app.selection.remove(item) },
                    guideOpen = guideOpen,
                    onOpenGuide = { guideOpen = true },
                    onCloseGuide = {
                        guideOpen = false
                        app.store.guideSeen = true
                    },
                    onRemoveFirst = {
                        app.selection.removeFirstOrNull()?.let { app.manager.notify("Removed ${it.name}") }
                    },
                    onMessageShown = { app.manager.consumeMessage(it) },
                )
            }
        }

        if (missing().isNotEmpty()) askPermissions.launch(requiredPermissions) else onPermissionsChanged()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        onPermissionsChanged()
    }

    private fun missing() = requiredPermissions.filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    private fun onPermissionsChanged() {
        val missing = missing()
        cameraAllowed = Manifest.permission.CAMERA !in missing
        // Notifications and camera are optional; the link itself needs the rest.
        val essential = missing.filter { it != Manifest.permission.CAMERA && it != Manifest.permission.POST_NOTIFICATIONS }
        missingPermissions = essential.isNotEmpty()
        if (essential.isEmpty()) {
            TransferService.start(this)
            app.manager.start()
        }
    }

    /** Android only lets the app on screen read the clipboard, so this happens here. */
    private fun sendClipboard(deviceId: String) {
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            app.manager.notify("Your clipboard is empty — copy something first")
            return
        }
        app.manager.sendClipboard(deviceId, text)
    }

    /** Swipe ↔ through the picked items: the next (or previous) one comes to the top. */
    private fun browse(step: Int) {
        val items = app.selection
        if (items.size < 2) return
        if (step > 0) items.add(items.removeAt(0)) else items.add(0, items.removeAt(items.lastIndex))
    }

    // Partial access ("selected photos only") on Android 14+ also counts.
    private fun hasMediaAccess() = mediaPermissions.any {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    /** "Show N fingers": selects the N most recent photos/videos (skipping ones Handsoff received). */
    private fun pickRecent(n: Int) {
        if (n <= 0) return
        lifecycleScope.launch {
            val recent = withContext(Dispatchers.IO) { recentMedia(n) }
            if (recent.isEmpty()) {
                app.manager.notify("No photos found on this phone")
                return@launch
            }
            app.selection.clear()
            app.selection.addAll(recent)
            app.manager.notify("Selected your latest ${if (recent.size == 1) "photo" else "${recent.size} photos"} — now make a fist, then open your hand to send")
        }
    }

    private fun recentMedia(n: Int): List<OutItem> {
        val found = mutableListOf<Pair<Long, OutItem>>()
        val collections = listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
        )
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        for (collection in collections) {
            try {
                contentResolver.query(
                    collection,
                    projection,
                    "${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE ?",
                    arrayOf("%Handsoff%"),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC",
                )?.use { c ->
                    var taken = 0
                    while (taken < n && c.moveToNext()) {
                        taken++
                        val uri = ContentUris.withAppendedId(collection, c.getLong(0))
                        val item = OutItem(uri, c.getString(1) ?: "photo", c.getString(2) ?: "image/jpeg", c.getLong(3))
                        found += c.getLong(4) to item
                    }
                }
            } catch (e: Exception) {
                /* no access to this collection */
            }
        }
        return found.sortedByDescending { it.first }.take(n).map { it.second }
    }

    /** Gallery / Files → Share → Handsoff. */
    private fun handleShare(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) addUris(uris)
    }

    private fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val picked = withContext(Dispatchers.IO) { uris.mapNotNull { describe(it) } }
            val known = app.selection.map { it.uri }.toSet()
            app.selection.addAll(picked.filter { it.uri !in known })
        }
    }

    private fun describe(uri: Uri): OutItem? = try {
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        OutItem(uri, name, contentResolver.getType(uri) ?: "application/octet-stream", size.coerceAtLeast(0))
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val MAX_PICK = 100
    }
}
