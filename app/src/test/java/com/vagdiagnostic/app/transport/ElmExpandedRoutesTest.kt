package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.ArrayDeque

/** Synthetic replies verify the routing contract, never physical compatibility. */
class ElmExpandedRoutesTest {
    private class Link(val missingList: Boolean = false, val wrong29: Boolean = false,
        val fail29: Boolean = false, val mismatchedList: Boolean = false) : ElmLink {
        override val label = "Expanded-route fixture"
        val sent = mutableListOf<String>()
        val incoming = ArrayDeque<Int>()
        val frames = ArrayDeque<String>()
        var protocol = "6"
        var tx = ""
        var filter = ""
        var sequence = 0
        var destination = 0
        override suspend fun open() {}
        override fun close() { incoming.clear() }
        override suspend fun readByte() = incoming.pollFirst() ?: -2
        private fun list(): String {
            val records = listOf("01010100", "02020100", "03030100", "04130100", "44090100",
                "15050100", "17070103", "25140103", "09200101", "46210101", "42220101",
                "52230101", "62240101", "72250101", "162A0101", "082C0101", "56521502", "191F050A")
                .map { if (mismatchedList && it.startsWith("04")) "04990100" else it }
            return "6204A1" + records.joinToString("") + (0x80..0xAE).joinToString("") { "%02X000000".format(it) }
        }
        private fun packet(hex: String): String {
            var payload = VagOemReadOnly.bytes(hex)
            val length = payload.size
            var first = true
            frames.clear()
            while (payload.isNotEmpty()) {
                val chunk = payload.take(if (first) 5 else 7)
                payload = payload.drop(chunk.size)
                val bytes = listOf((if (payload.isEmpty()) 0x10 else 0) or sequence) +
                    (if (first) listOf(length ushr 8, length and 255) else emptyList()) + chunk
                frames.add("300" + VagOemReadOnly.hex(bytes))
                first = false
                sequence = (sequence + 1) and 15
            }
            return frames.removeFirst()
        }
        override suspend fun write(bytes: ByteArray) {
            val cmd = bytes.toString(Charsets.US_ASCII).trim()
            sent += cmd
            val raw = when {
                cmd == "ATZ" -> "ELM327"
                cmd == "0100" -> "7E8064100BE3EA813"
                cmd.startsWith("ATSP") -> { protocol = cmd.removePrefix("ATSP"); "OK" }
                cmd == "ATDPN" -> protocol
                cmd.startsWith("ATSH") -> { tx = cmd.removePrefix("ATSH"); "OK" }
                cmd.startsWith("ATCRA") -> { filter = cmd.removePrefix("ATCRA"); "OK" }
                cmd.startsWith("AT") -> "OK"
                cmd.endsWith("C000100003011") -> {
                    destination = cmd.take(2).toInt(16)
                    sequence = 0
                    if (destination in setOf(0x21, 5, 7, 0x2A, 0x2C)) "NO DATA"
                    else "%03X00D00003000701".format(0x200 or destination)
                }
                cmd == "A00194FF82FF" -> "300A10F94FF82FF"
                cmd == "A80" || (cmd.startsWith("B") && cmd.length == 3) -> ""
                cmd.startsWith("B") -> frames.removeFirst()
                cmd == "1003" -> {
                    if (protocol == "7" && fail29) throw IOException("fixture stream failure")
                    if (tx == "70D") "NO DATA"
                    else (if (protocol == "7" && wrong29) "17FE008A" else filter) + "065003003201F4"
                }
                cmd == "1001" -> filter + "065001003201F4"
                cmd == "19028D" -> if (protocol == "7")
                    filter + "100B590219123456\r" + filter + "2108654321040000"
                    else filter + "03590219"
                cmd.startsWith("1") -> {
                    val payload = when (val request = cmd.drop(6)) {
                        "1089" -> "5089"
                        "1A9F" -> if (missingList) "NO DATA" else "7F1A11"
                        "2204A1" -> if (missingList) "NO DATA" else list()
                        "1800FF00" -> "5800"
                        "1802FF00" -> if (destination == 0x1F) "5802051924051824" else "5800"
                        else -> error(request)
                    }
                    if (payload == "NO DATA") payload else
                        "300B%X\r".format((cmd.take(2).toInt(16) + 1) and 15) + packet(payload)
                }
                else -> error("Unexpected fixture command $cmd")
            }
            (raw + ">").toByteArray(Charsets.US_ASCII).forEach { incoming.add(it.toInt() and 255) }
        }
    }
    private suspend fun scan(link: Link, units: MutableMap<String, ControlUnit>): ElmVagScan.Outcome {
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        return ElmVagScan.scan(engine, { units[it.address] = it }, { _, _, _ -> })
    }
    @Test fun allReported18HaveRoutesAndComfortUsesBounded29BitAlternative() = runBlocking {
        val link = Link()
        val units = linkedMapOf<String, ControlUnit>()
        val result = scan(link, units)
        assertTrue(result.note, result.complete)
        assertEquals(18, units.size)
        assertTrue(units.values.all { it.state == ReadState.COMPLETE })
        for (tp in listOf("13", "14", "24", "25", "52")) assertTrue(link.sent.contains(tp + "C000100003011"))
        assertTrue(link.sent.containsAll(listOf("ATCRA777", "ATSP7", "ATCP17", "ATSHFC008B", "ATCRA17FE008B",
            "ATFCSH17FC008B", "ATFCSD300000", "ATFCSM1")))
        assertEquals(2, units.getValue("VAG-46").dtcs.size)
        assertTrue(link.sent.indexOf("ATFCSM1") < link.sent.indexOf("1003", link.sent.indexOf("ATSP7")))
        val next11 = link.sent.indexOf("ATSH70C")
        assertTrue(link.sent.indexOf("ATFCSM0") in link.sent.indexOf("ATSP7") until next11)
        assertFalse(link.sent.any { it == "04" || it.startsWith("27") || it.startsWith("2E") })
    }
    @Test fun addedRoutesAnd29BitAlternativeRequireInstallationList() = runBlocking {
        val link = Link(missingList = true)
        val units = linkedMapOf<String, ControlUnit>()
        assertFalse(scan(link, units).listConfirmed)
        assertEquals(14, units.size)
        for (tp in listOf("13", "14", "24", "25", "52")) assertFalse(link.sent.contains(tp + "C000100003011"))
        assertFalse(link.sent.contains("ATSP7"))
    }
    @Test fun conflictingAdvertisedTpAddressIsNeitherGuessedNorUsed() = runBlocking {
        val link = Link(mismatchedList = true)
        val units = linkedMapOf<String, ControlUnit>()
        assertFalse(scan(link, units).complete)
        assertEquals(ReadState.NOT_QUERIED, units.getValue("VAG-04").state)
        assertFalse(link.sent.contains("13C000100003011"))
        assertFalse(link.sent.contains("99C000100003011"))
    }
    @Test fun wrong29BitEcuOrBrokenStreamAbortsWithoutSessionCloseOrLaterEcu() = runBlocking {
        for (link in listOf(Link(wrong29 = true), Link(fail29 = true))) {
            val units = linkedMapOf<String, ControlUnit>()
            try { scan(link, units); fail("Expected strict 29-bit failure") } catch (_: IOException) {}
            assertEquals("1003", link.sent.last())
            assertEquals(ReadState.NO_RESPONSE, units.getValue("VAG-46").state)
            assertFalse(link.sent.contains("22C000100003011"))
        }
    }
    private fun List<String>.indexOf(value: String, from: Int): Int =
        (from until size).firstOrNull { this[it] == value } ?: -1
}
