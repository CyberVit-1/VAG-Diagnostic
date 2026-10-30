package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.*
import java.io.IOException
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic fallback responses, not a vehicle recording. */
class ElmVagCandidateDiscoveryTest {
    private class Link(val corruptAt: Int? = null, val cancelAt: Int? = null,
        val gatewayAvailable: Boolean = true, val activeFilter: Boolean = false, val gatewayPart: String? = "5Q0907530") : ElmLink {
        override val label = "Synthetic fixed-catalog fallback"
        val sent = mutableListOf<String>()
        val addressed = mutableListOf<Pair<Int?, String>>()
        private val queue = ArrayDeque<Int>()
        private var protocol = "6"
        private var rx = 0x77A
        private val byRx = VagOemCandidateCatalog.routes.values.filter { it.udsRx != null }.associateBy { it.udsRx }
        private val available = setOf(9, 0xFFFF) + if (gatewayAvailable) setOf(0x19) else emptySet()
        override suspend fun open() {}
        override fun close() { queue.clear() }
        override suspend fun readByte() = queue.pollFirst() ?: -2
        private fun payload(hex: String): String {
            val header = if (rx <= 0x7FF) "%03X".format(rx) else "%08X".format(rx)
            val length = hex.length / 2
            if (length <= 7) return header + "%02X".format(length) + hex
            return header + "10%02X".format(length) + hex.take(12) + "\r" + hex.drop(12).chunked(14).mapIndexed { i, chunk ->
                header + "%02X".format(0x21 + i) + chunk.padEnd(14, '0')
            }.joinToString("\r")
        }
        override suspend fun write(bytes: ByteArray) {
            val cmd = bytes.toString(Charsets.US_ASCII).trim()
            sent += cmd
            if (cmd.startsWith("ATCRA") && cmd.length > 5) rx = cmd.drop(5).toInt(16)
            val id = byRx[rx]?.id
            if (!cmd.startsWith("AT")) addressed += id to cmd
            val answer = when {
                cmd == "ATZ" -> "ELM327 v1.5"
                cmd == "ATSP6" || cmd == "ATSP7" -> { protocol = cmd.takeLast(1); "OK" }
                cmd == "ATDPN" -> protocol
                cmd.startsWith("AT") -> "OK"
                cmd.endsWith("C000100003011") -> "NO DATA"
                cmd == "0100" -> "7E8064100BE3EA813"
                id == cancelAt && cmd == "3E00" -> throw CancellationException("Stop")
                id == corruptAt && cmd == "3E00" -> payload("7E01")
                id !in available -> "NO DATA"
                cmd == "3E00" -> payload("7E00")
                cmd == "1003" -> payload("5003")
                cmd == "1001" -> payload("5001")
                cmd == "22F187" -> payload(when (id) {
                    0x19 -> gatewayPart?.let { "62F187" + VagOemReadOnly.hex(it.map { char -> char.code }) } ?: "7F2231"
                    9 -> "62F187" + VagOemReadOnly.hex("5Q0937087".map { it.code })
                    else -> "7F2231"
                })
                cmd in setOf("222A26", "2204A1") -> payload("7F2231")
                cmd == "22539B" && activeFilter -> payload("62539B0101")
                cmd in setOf("22F197", "22F189", "22F19E", "220174", "22539B") -> payload("7F2231")
                cmd == "19028D" -> payload(if (id == 0xFFFF) "59028D12345608" else "59028D")
                else -> error("Unexpected $id/$cmd")
            }
            (answer + ">").toByteArray(Charsets.US_ASCII).forEach { queue.add(it.toInt() and 255) }
        }
    }
    @Test fun fixedCatalogIncludesExtendedLogicalIdsWithoutPromotingSilentCandidates() = runBlocking {
        val link = Link()
        val units = linkedMapOf<String, ControlUnit>()
        val result = ElmVagScan.scan(ElmEngine(link, { _, _, _, _ -> }), { units[it.address] = it }, { _, _, _ -> }, allowCandidateFallback = true)
        assertFalse(result.listConfirmed)
        assertFalse(result.complete) // Scope cannot be proved without an installation list.
        assertEquals(189, units.size)
        val found = units.values.filter { it.source != "CANDIDATE" }
        assertEquals(setOf("VAG-19", "VAG-09", "VAG-FFFF"), found.map { it.address }.toSet())
        assertTrue(found.all { it.state == ReadState.COMPLETE })
        assertEquals("123456", units.getValue("VAG-FFFF").dtcs.single().code)
        assertTrue(units.values.filter { it.source == "CANDIDATE" }.all { it.state == ReadState.NO_RESPONSE && it.dtcs.isEmpty() })
        assertTrue(link.sent.contains("ATFCSH17FC0077"))
        assertFalse(link.sent.contains("ATSH718")) // No additional radio address.
        assertEquals(1, link.addressed.count { it == (0xFFFF to "19028D") })
    }
    @Test fun activeFilterDoesNotHideRespondingFallbackEcus() = runBlocking {
        val link = Link(activeFilter = true)
        val units = linkedMapOf<String, ControlUnit>()
        val result = ElmVagScan.scan(ElmEngine(link, { _, _, _, _ -> }), { units[it.address] = it }, { _, _, _ -> }, allowCandidateFallback = true)
        assertFalse(result.complete)
        assertEquals(189, units.size)
        assertEquals(setOf("VAG-19", "VAG-09", "VAG-FFFF"), units.values.filter { it.source != "CANDIDATE" }.map { it.address }.toSet())
        assertEquals("123456", units.getValue("VAG-FFFF").dtcs.single().code)
        assertTrue(result.note.contains("фильтр"))
        assertFalse(link.sent.any { it.startsWith("2E") || it.startsWith("27") || it.startsWith("29") })
    }
    @Test fun verifiedGatewayPartEnablesAutomaticCatalogWithoutVin() = runBlocking {
        val link = Link()
        val units = linkedMapOf<String, ControlUnit>()
        val events = mutableListOf<String>()
        val result = ElmVagScan.scan(ElmEngine(link, { _, _, _, _ -> }), { units[it.address] = it },
            { command, _, _ -> events += command }, allowCandidateFallback = false)
        assertFalse(result.listConfirmed)
        assertFalse(result.complete)
        assertEquals(189, units.size)
        assertEquals(setOf("VAG-19", "VAG-09", "VAG-FFFF"), units.values.filter { it.source != "CANDIDATE" }.map { it.address }.toSet())
        assertEquals("123456", units.getValue("VAG-FFFF").dtcs.single().code)
        assertTrue(events.contains("VAG_DISCOVERY_SCOPE"))
        assertFalse(link.sent.any { it.startsWith("09") })
    }
    @Test fun callerCanRequireAnInstallationListDespiteKnownGatewayPart() = runBlocking {
        val link = Link()
        val units = mutableListOf<ControlUnit>()
        val result = ElmVagScan.scan(ElmEngine(link, { _, _, _, _ -> }), { units += it }, { _, _, _ -> },
            allowCandidateFallback = false, allowGatewayIdentityFallback = false)
        assertFalse(result.complete)
        assertTrue(units.isEmpty())
        assertFalse(link.sent.contains("19028D"))
        assertFalse(link.sent.contains("ATFCSH17FC0077"))
    }
    @Test fun unverifiedManufacturerAndUnknownGatewayCannotStartCatalogFallback() = runBlocking {
        val link = Link(gatewayPart = null)
        val units = mutableListOf<ControlUnit>()
        val result = ElmVagScan.scan(ElmEngine(link, { _, _, _, _ -> }), { units += it }, { _, _, _ -> }, allowCandidateFallback = false)
        assertFalse(result.listConfirmed)
        assertTrue(units.isEmpty())
        assertFalse(link.sent.contains("19028D"))
        assertFalse(link.sent.contains("ATFCSH17FC0077"))
    }
    @Test fun verifiedManufacturerCanDiscoverActualEcusWhenGatewayDoesNotAnswer() = runBlocking {
        val link = Link(gatewayAvailable = false)
        val units = linkedMapOf<String, ControlUnit>()
        val result = ElmVagScan.scan(ElmEngine(link, { _, _, _, _ -> }), { units[it.address] = it }, { _, _, _ -> },
            allowCandidateFallback = true, readGatewayIdentity = true)
        assertNull(result.protocol)
        assertFalse(result.listConfirmed)
        assertFalse(result.complete)
        assertEquals(setOf("VAG-09", "VAG-FFFF"), units.values.filter { it.source != "CANDIDATE" }.map { it.address }.toSet())
        assertEquals(ReadState.COMPLETE, units.getValue("VAG-FFFF").state)
        assertEquals(ReadState.NO_RESPONSE, units.getValue("VAG-19").state)
        assertEquals("CANDIDATE", units.getValue("VAG-19").source)
        assertEquals(1, link.addressed.count { it == (0x19 to "3E00") })
        assertFalse(link.addressed.contains(0x19 to "19028D"))
    }
    @Test fun fatalCandidateReplyOrCancellationStopsQueueAndPreservesEarlierDtc() = runBlocking {
        for (cancel in listOf(false, true)) {
            val link = Link(corruptAt = if (cancel) null else 1, cancelAt = if (cancel) 1 else null)
            val units = linkedMapOf<String, ControlUnit>()
            val failure = runCatching { ElmVagScan.scan(ElmEngine(link, { _, _, _, _ -> }),
                { units[it.address] = it }, { _, _, _ -> }, allowCandidateFallback = true) }.exceptionOrNull()
            if (cancel) assertTrue(failure is CancellationException) else assertTrue(failure is IOException)
            assertEquals(ReadState.COMPLETE, units.getValue("VAG-19").state)
            assertFalse(units.containsKey("VAG-FFFF"))
            assertEquals("3E00", link.sent.last())
        }
    }
}
