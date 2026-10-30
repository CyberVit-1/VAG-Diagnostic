package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.*
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Scripted wire scenarios; none is a recording from a vehicle. */
class ElmFunctionalAndPendingTest {
    private class Link(val respond: (String) -> String) : ElmLink {
        override val label = "Synthetic functional/ISO-TP fixture"
        val sent = mutableListOf<String>()
        val queue = ArrayDeque<Int>()
        var closed = false
        override suspend fun open() {}
        override fun close() { closed = true; queue.clear() }
        override suspend fun readByte() = queue.pollFirst() ?: -2
        override suspend fun write(bytes: ByteArray) {
            val cmd = bytes.toString(Charsets.US_ASCII).trim()
            sent += cmd
            respond(cmd).toByteArray(Charsets.US_ASCII).forEach { queue.add(it.toInt() and 255) }
        }
    }
    private fun hex(data: List<Int>) = data.joinToString("") { "%02X".format(it) }
    private fun bitmap(vararg bits: Int): List<Int> {
        val bytes = MutableList(32) { 0 }
        bits.forEach { bytes[it / 8] = bytes[it / 8] or (0x80 ushr (it % 8)) }
        return listOf(0x62, 0x2A, 0x26) + bytes
    }
    private fun first(payload: List<Int>) = "77A10%02X".format(payload.size) + hex(payload.take(6))
    private fun tail(payload: List<Int>) = payload.drop(6).chunked(7).mapIndexed { i, chunk ->
        "77A%02X".format(0x21 + i) + hex(chunk)
    }.joinToString("\r")
    private fun bitsFor(id: Int): Int = (0..255).single { bit ->
        VagInstallationBitmap.decode(0x19, "5WA907530", bitmap(bit)).ids.contains(id)
    }
    @Test fun interleavedOwnersMergeTheirOwnListsAndUsePhysicalGatewayFc() = runBlocking {
        val payload = bitmap(7, bitsFor(0x42), bitsFor(0x52), bitsFor(9))
        val link = Link { cmd -> when (cmd) {
            "023E800000000000" -> "NO DATA\r>"
            "03222A2600000000" -> first(payload) + "\r7B404622A2603\r7B504622A2603\r77805622A261100\r>"
            "3000100000000000" -> tail(payload) + "\r>"
            else -> if (cmd.startsWith("AT")) "OK\r>" else error(cmd)
        } }
        val result = ElmVagFunctionalList.read(ElmEngine(link, { _, _, _, _ -> }), "5WA907530")
        assertTrue(result.limitations.toString(), result.complete)
        assertEquals(setOf(0x19, 0x42, 0x52, 9), result.respondingOwners)
        assertEquals(setOf(0x42), result.installed.single { it.id == 0xBB }.listOwners)
        assertEquals(setOf(0x52), result.installed.single { it.id == 0xBC }.listOwners)
        assertEquals(setOf(9), result.installed.single { it.id == 5 }.listOwners)
        val fc = link.sent.indexOf("3000100000000000")
        assertEquals("ATSH710", link.sent[fc - 1])
        assertEquals(1, link.sent.count { it == "03222A2600000000" })
        assertFalse(link.closed)
    }
    @Test fun gatewayOnlyDoesNotInventMissingDoorsAndLateFirstFrameIsCollected() = runBlocking {
        val payload = bitmap(7)
        val link = Link { cmd -> when (cmd) {
            "023E800000000000" -> "NO DATA\r>"
            "03222A2600000000" -> "77A037F2278\r>"
            "ATMA" -> first(payload) + "\r"
            "" -> "STOPPED\r>"
            "3000100000000000" -> tail(payload) + "\r>"
            else -> "OK\r>"
        } }
        val result = ElmVagFunctionalList.read(ElmEngine(link, { _, _, _, _ -> }), "5WA907530")
        assertTrue(result.limitations.toString(), result.complete)
        assertEquals(listOf(0x19), result.installed.map { it.id })
        assertEquals(1, link.sent.count { it == "ATMA" })
    }
    @Test fun missingAdvertisedChildKeepsGatewayEvidenceButDiscardsConnection() = runBlocking {
        val payload = bitmap(7, bitsFor(0x42))
        val link = Link { cmd -> when (cmd) {
            "023E800000000000" -> "NO DATA\r>"
            "03222A2600000000" -> first(payload) + "\r>"
            "3000100000000000" -> tail(payload) + "\r>"
            "ATMA" -> "BUFFER FULL\r"
            else -> "OK\r>"
        } }
        val result = ElmVagFunctionalList.read(ElmEngine(link, { _, _, _, _ -> }), "5WA907530")
        assertFalse(result.complete)
        assertTrue(result.requiresReconnect)
        assertTrue(link.closed)
        assertTrue(result.installed.any { it.id == 0x42 })
        assertTrue(result.limitations.any { it.contains("42") })
    }
    @Test fun pendingPhysicalReplyHandlesLateFirstFrameWithoutRequestResend() = runBlocking {
        for (target in listOf(VagOemReadOnly.routes.getValue(0x19),
            VagOemReadOnly.Route(0x46, "Synthetic", null, 0x17FC008B))) {
            val rx = "%0${if (target.is29Bit) 8 else 3}X".format(target.udsRx)
            val link = Link { cmd -> when (cmd) {
                "ATMA" -> "${rx}100759028D123456\r"
                "" -> "STOPPED\r>"
                "3000100000000000" -> "${rx}2124\r>"
                else -> "OK\r>"
            } }
            // ISO-TP length must exceed SF capacity: 11-byte payload.
            val link2 = Link { cmd -> when (cmd) {
                "ATMA" -> "${rx}100B59028D123456\r"
                "" -> "STOPPED\r>"
                "3000100000000000" -> "${rx}21246543212400\r>"
                else -> "OK\r>"
            } }
            val result = ElmUdsPending.read(ElmEngine(link2, { _, _, _, _ -> }), target,
                "19028D", "${rx}037F1978", 100, 1)
            assertEquals(11, result.size)
            assertEquals(listOf("ATCFC0", "ATCAF0", "ATMA", "", "3000100000000000", "ATCAF1", "ATCFC1"), link2.sent)
            assertFalse(link2.closed)
            assertTrue(runCatching { ElmUdsPending.read(ElmEngine(link, { _, _, _, _ -> }), target,
                "19028D", "${rx}037F1978", 100, 1) }.isFailure)
            assertTrue(link.closed)
        }
    }
    @Test fun pendingCancellationClosesWithoutFcOrSessionClose() = runBlocking {
        val link = Link { cmd -> if (cmd == "ATMA") throw CancellationException("Stop") else "OK\r>" }
        val error = runCatching { ElmUdsPending.read(ElmEngine(link, { _, _, _, _ -> }),
            VagOemReadOnly.routes.getValue(0x19), "19028D", "77A037F1978", 100, 1) }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertTrue(link.closed)
        assertFalse(link.sent.contains("1001"))
        assertFalse(link.sent.contains("3000100000000000"))
    }
    @Test fun functionalCancellationCannotContinueIntoFcOrAnotherRequest() = runBlocking {
        val link = Link { cmd -> when (cmd) {
            "023E800000000000" -> "NO DATA\r>"
            "03222A2600000000" -> throw CancellationException("Stop")
            else -> "OK\r>"
        } }
        val error = runCatching { ElmVagFunctionalList.read(ElmEngine(link, { _, _, _, _ -> }), "5WA907530") }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertTrue(link.closed)
        assertEquals("03222A2600000000", link.sent.last())
    }
}
