package com.handsoff.app.nearby

import org.json.JSONArray
import org.json.JSONObject

// Messages exchanged between two Handsoff phones over Nearby Connections.
//
//   endpoint name:  "HS1|<deviceId>|<display name>"   (how phones recognise each other)
//                   "HSB|…" when asking to connect in fist-bump mode
//   batch manifest: {"t":"batch","id","from","items":[{"pid","name","mime","size"}]}
//   then one FILE payload per item (payload id = pid)
//   receipt:        {"t":"ack","id"}
//   fist-bump:      {"t":"bump"} when this phone saw a bump, {"t":"bump-ok"} when it paired
//   reaction:       {"t":"react","id","emoji"}   (👍 / ✌️ shown to the camera by the receiver)
//   unsend:         {"t":"unsend","id"}           (sender waved; receiver drops the batch)
//   clipboard:      {"t":"clip","text"}

object Protocol {
    private const val PREFIX = "HS1"
    private const val BUMP_PREFIX = "HSB"
    const val MAX_ITEMS = 500
    const val MAX_CLIP = 10_000
    val REACTIONS = setOf("👍", "✌️")

    data class Identity(val deviceId: String, val name: String, val bump: Boolean = false)

    data class ItemMeta(val payloadId: Long, val name: String, val mime: String, val size: Long)

    sealed interface Message
    data class Batch(val id: String, val from: String, val items: List<ItemMeta>) : Message
    data class Ack(val id: String) : Message
    data object Bump : Message
    data object BumpOk : Message
    data class React(val id: String, val emoji: String) : Message
    data class Unsend(val id: String) : Message
    data class Clip(val text: String) : Message

    fun endpointName(deviceId: String, name: String, bump: Boolean = false): String {
        val clean = name.replace("|", " ").trim().take(40).ifEmpty { "Phone" }
        return "${if (bump) BUMP_PREFIX else PREFIX}|$deviceId|$clean"
    }

    fun parseEndpointName(raw: String): Identity? {
        val parts = raw.split("|", limit = 3)
        if (parts.size != 3 || (parts[0] != PREFIX && parts[0] != BUMP_PREFIX)) return null
        val id = parts[1]
        if (!Regex("^[a-z0-9]{8,32}$").matches(id)) return null
        return Identity(id, parts[2].take(40).ifEmpty { "Phone" }, bump = parts[0] == BUMP_PREFIX)
    }

    fun encode(msg: Message): ByteArray {
        val o = JSONObject()
        when (msg) {
            is Batch -> {
                o.put("t", "batch").put("id", msg.id).put("from", msg.from)
                val arr = JSONArray()
                for (it in msg.items) {
                    arr.put(JSONObject().put("pid", it.payloadId).put("name", it.name).put("mime", it.mime).put("size", it.size))
                }
                o.put("items", arr)
            }
            is Ack -> o.put("t", "ack").put("id", msg.id)
            Bump -> o.put("t", "bump")
            BumpOk -> o.put("t", "bump-ok")
            is React -> o.put("t", "react").put("id", msg.id).put("emoji", msg.emoji)
            is Unsend -> o.put("t", "unsend").put("id", msg.id)
            is Clip -> o.put("t", "clip").put("text", msg.text)
        }
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): Message? = try {
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        when (o.optString("t")) {
            "batch" -> {
                val arr = o.getJSONArray("items")
                if (arr.length() == 0 || arr.length() > MAX_ITEMS) null
                else Batch(
                    id = o.getString("id").take(40),
                    from = o.optString("from").take(40),
                    items = (0 until arr.length()).map { i ->
                        val it = arr.getJSONObject(i)
                        ItemMeta(
                            payloadId = it.getLong("pid"),
                            name = cleanName(it.optString("name")),
                            mime = cleanMime(it.optString("mime")),
                            size = it.optLong("size").coerceAtLeast(0),
                        )
                    },
                )
            }
            "ack" -> Ack(o.getString("id").take(40))
            "bump" -> Bump
            "bump-ok" -> BumpOk
            "react" -> o.getString("emoji").takeIf { it in REACTIONS }?.let { React(o.getString("id").take(40), it) }
            "unsend" -> Unsend(o.getString("id").take(40))
            "clip" -> o.getString("text").takeIf { it.isNotEmpty() }?.let { Clip(it.take(MAX_CLIP)) }
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    fun cleanName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").take(120).ifBlank { "file" }

    fun cleanMime(mime: String): String =
        if (Regex("^[\\w.+-]+/[\\w.+-]+$").matches(mime)) mime.take(100) else "application/octet-stream"
}
