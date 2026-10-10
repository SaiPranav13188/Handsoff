package com.handsoff.app.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

data class PairedDevice(val id: String, val name: String)

data class ReceivedEntry(val uri: String, val name: String, val mime: String, val from: String, val time: Long)

/** This phone's identity, its paired phones (kept until removed) and recent received items. */
class DeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences("handsoff", Context.MODE_PRIVATE)

    val myId: String = prefs.getString(KEY_ID, null) ?: newId().also { prefs.edit().putString(KEY_ID, it).apply() }

    val myName: String = run {
        val system = try {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (e: Exception) {
            null
        }
        (system?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}").take(40)
    }

    fun paired(): List<PairedDevice> = readArray(KEY_PAIRED).map { PairedDevice(it.getString("id"), it.getString("name")) }

    fun isPaired(id: String) = paired().any { it.id == id }

    fun savePaired(device: PairedDevice) {
        val list = paired().filter { it.id != device.id } + device
        writeArray(KEY_PAIRED, list.map { JSONObject().put("id", it.id).put("name", it.name) })
    }

    fun removePaired(id: String) {
        writeArray(KEY_PAIRED, paired().filter { it.id != id }.map { JSONObject().put("id", it.id).put("name", it.name) })
    }

    fun received(): List<ReceivedEntry> = readArray(KEY_RECEIVED).map {
        ReceivedEntry(it.getString("uri"), it.getString("name"), it.getString("mime"), it.getString("from"), it.getLong("time"))
    }

    fun addReceived(entries: List<ReceivedEntry>) {
        val list = (entries + received()).take(MAX_RECEIVED)
        writeArray(KEY_RECEIVED, list.map {
            JSONObject().put("uri", it.uri).put("name", it.name).put("mime", it.mime).put("from", it.from).put("time", it.time)
        })
    }

    fun removeReceived(uris: Set<String>) {
        writeArray(KEY_RECEIVED, received().filter { it.uri !in uris }.map {
            JSONObject().put("uri", it.uri).put("name", it.name).put("mime", it.mime).put("from", it.from).put("time", it.time)
        })
    }

    /** Trip mode: new photos taken on this phone go to every paired phone. */
    var tripMode: Boolean
        get() = prefs.getBoolean(KEY_TRIP, false)
        set(value) = prefs.edit().putBoolean(KEY_TRIP, value).apply()

    /** Highest MediaStore id already handled by Trip mode, per collection. */
    fun tripLastId(collection: String): Long = prefs.getLong("$KEY_TRIP_LAST$collection", -1L)

    fun setTripLastId(collection: String, id: Long) = prefs.edit().putLong("$KEY_TRIP_LAST$collection", id).apply()

    /** Whether the "How to use" guide has been shown (it opens by itself on first launch). */
    var guideSeen: Boolean
        get() = prefs.getBoolean(KEY_GUIDE, false)
        set(value) = prefs.edit().putBoolean(KEY_GUIDE, value).apply()

    var tripShared: Int
        get() = prefs.getInt(KEY_TRIP_SHARED, 0)
        set(value) = prefs.edit().putInt(KEY_TRIP_SHARED, value).apply()

    private fun readArray(key: String): List<JSONObject> = try {
        val arr = JSONArray(prefs.getString(key, "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun writeArray(key: String, items: List<JSONObject>) {
        prefs.edit().putString(key, JSONArray(items).toString()).apply()
    }

    companion object {
        private const val KEY_ID = "device_id"
        private const val KEY_PAIRED = "paired"
        private const val KEY_RECEIVED = "received"
        private const val MAX_RECEIVED = 60
        private const val KEY_TRIP = "trip_mode"
        private const val KEY_TRIP_LAST = "trip_last_"
        private const val KEY_TRIP_SHARED = "trip_shared"
        private const val KEY_GUIDE = "guide_seen"

        private fun newId(): String {
            val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
            val rnd = SecureRandom()
            return (1..16).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        }
    }
}
