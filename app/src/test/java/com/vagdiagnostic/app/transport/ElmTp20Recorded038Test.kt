package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.VagOemReadOnly
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual 0.3.8 ABS identity exchange, with no vehicle identifier or endpoint. */
class ElmTp20Recorded038Test {
    private class Replay(val splitDuplicate: Boolean = false, val alter: (String) -> String = { it }) : ElmLink {
        override val label = "Recorded ABS identity"
        val rows = requireNotNull(javaClass.getResourceAsStream("/replay/vehicle-038-abs-identity.tsv"))
            .bufferedReader().readLines().map { line -> line.split('\t', limit = 2).let {
                it[0] to String(Base64.getDecoder().decode(it[1]), Charsets.UTF_8)
            } }
        var index = 0
        var repeatedAcks = 0
        private val bytes = ArrayDeque<Int>()
        override suspend fun open() {}
        override fun close() {}
        override suspend fun readByte() = bytes.removeFirstOrNull() ?: -2
        override suspend fun write(bytes: ByteArray) {
            val command = bytes.toString(Charsets.US_ASCII).trim()
            if (splitDuplicate && index == rows.size && command == "B9") {
                repeatedAcks++
                this.bytes.addAll("3001920>".toByteArray().map { it.toInt() and 255 })
                return
            }
            // The real process stopped before the final ACK. This empty ACK
            // response is synthetic and is not appended to the recording.
            if (index == rows.size && command == "BA0") {
                this.bytes.add('>'.code)
                return
            }
            val row = rows[index++]
            check(command == row.first) { "Row $index: expected ${row.first}, got $command" }
            val reply = if (command == "B9") alter(row.second).let {
                if (splitDuplicate) it.substringBefore("\r3001920") else it
            } else row.second
            this.bytes.addAll((reply + ">").toByteArray().map { it.toInt() and 255 })
        }
    }
    @Test fun exactRepeatedLastFrameDoesNotDuplicateIdentityBytes() = runBlocking {
        val link = Replay()
        val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
        channel.open(3)
        channel.request("1089")
        val identity = channel.requestMessages("1A9B")
        assertEquals(1, identity.size)
        assertEquals(48, identity.single().size)
        assertEquals("5A9B314B30393037333739414B", VagOemReadOnly.hex(identity.single().take(13)))
        assertEquals(link.rows.size, link.index)
    }
    @Test fun differentBytesAtTheSameSequenceAreStillRejected() = runBlocking {
        val link = Replay { it.replace("3000820202020202020", "3000820202020202021") }
        val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
        channel.open(3); channel.request("1089")
        assertTrue(runCatching { channel.requestMessages("1A9B") }.exceptionOrNull() is java.io.IOException)
    }
    @Test fun isolatedDuplicateAcknowledgesTheExpectedSequenceWithoutReissuingTheService() = runBlocking {
        val link = Replay(splitDuplicate = true)
        val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
        channel.open(3); channel.request("1089")
        assertEquals(48, channel.requestMessages("1A9B").single().size)
        assertEquals(1, link.repeatedAcks)
        assertEquals(link.rows.size, link.index)
    }
    @Test fun repeatedDuplicatesAreBoundedBeforeTheRemainingPayloadIsAccepted() = runBlocking {
        val link = Replay { it.replace("3000820202020202020", List(4) { "3000820202020202020" }.joinToString("\r")) }
        val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
        channel.open(3); channel.request("1089")
        val error = runCatching { channel.requestMessages("1A9B") }.exceptionOrNull()
        assertTrue(error.toString(), error is java.io.IOException)
        assertTrue(error?.message.orEmpty().contains("предел"))
    }
}
