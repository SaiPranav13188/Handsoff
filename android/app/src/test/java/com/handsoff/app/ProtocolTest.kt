package com.handsoff.app

import com.handsoff.app.nearby.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolTest {
    @Test
    fun endpointNameRoundTrips() {
        val raw = Protocol.endpointName("abc123def456", "Sai's | Pixel")
        assertEquals(Protocol.Identity("abc123def456", "Sai's   Pixel"), Protocol.parseEndpointName(raw))
    }

    @Test
    fun bumpRequestsAreMarked() {
        val id = Protocol.parseEndpointName(Protocol.endpointName("abc123def456", "Pixel", bump = true))
        assertEquals(Protocol.Identity("abc123def456", "Pixel", bump = true), id)
    }

    @Test
    fun foreignEndpointNamesAreIgnored() {
        assertNull(Protocol.parseEndpointName("Galaxy S24"))
        assertNull(Protocol.parseEndpointName("HS1|BAD ID|x"))
    }

    @Test
    fun batchRoundTripsAndCleansInput() {
        val batch = Protocol.Batch(
            "b1", "Phone A",
            listOf(
                Protocol.ItemMeta(11, "IMG_1.jpg", "image/jpeg", 2048),
                Protocol.ItemMeta(12, "../evil:name.mp4", "video/mp4", 99),
            ),
        )
        val back = Protocol.decode(Protocol.encode(batch)) as Protocol.Batch
        assertEquals("b1", back.id)
        assertEquals(listOf(11L, 12L), back.items.map { it.payloadId })
        assertEquals(".._evil_name.mp4", back.items[1].name)
    }

    @Test
    fun ackRoundTripsAndJunkIsRejected() {
        assertEquals(Protocol.Ack("b9"), Protocol.decode(Protocol.encode(Protocol.Ack("b9"))))
        assertEquals(Protocol.Bump, Protocol.decode(Protocol.encode(Protocol.Bump)))
        assertEquals(Protocol.BumpOk, Protocol.decode(Protocol.encode(Protocol.BumpOk)))
        assertEquals(Protocol.React("b9", "👍"), Protocol.decode(Protocol.encode(Protocol.React("b9", "👍"))))
        assertEquals(Protocol.Unsend("b9"), Protocol.decode(Protocol.encode(Protocol.Unsend("b9"))))
        assertEquals(Protocol.Clip("https://maps.app/x"), Protocol.decode(Protocol.encode(Protocol.Clip("https://maps.app/x"))))
        // Only known reactions; empty clipboards are dropped; huge ones are cut.
        assertNull(Protocol.decode("""{"t":"react","id":"x","emoji":"<script>"}""".toByteArray()))
        assertNull(Protocol.decode("""{"t":"clip","text":""}""".toByteArray()))
        val big = Protocol.decode(Protocol.encode(Protocol.Clip("a".repeat(20_000)))) as Protocol.Clip
        assertEquals(Protocol.MAX_CLIP, big.text.length)
        assertNull(Protocol.decode("not json".toByteArray()))
        assertNull(Protocol.decode("""{"t":"batch","id":"x","items":[]}""".toByteArray()))
        assertEquals("application/octet-stream", Protocol.cleanMime("text/html; evil"))
    }
}
