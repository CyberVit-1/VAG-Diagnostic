package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.*
import java.util.Base64
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Recorded OEM exchanges, excluding VIN, timestamps and adapter endpoint.
 * New presence exchange and metadata substitutions are explicitly synthetic,
 * not vehicle evidence. Original fixture files remain unchanged. */
class ElmVehicleReplayTest {
    private data class Row(val command: String, val raw: String)
    private class Replay(val rows: List<Row>, val failAt: Int? = null) : ElmLink {
        override val label = "Recorded byte stream"
        var index = 0
        val sent = mutableListOf<String>()
        private val bytes = ArrayDeque<Int>()
        override suspend fun open() {}
        override fun close() { bytes.clear() }
        override suspend fun readByte(): Int = bytes.removeFirstOrNull() ?: -2
        override suspend fun write(data: ByteArray) {
            val command = data.toString(Charsets.US_ASCII).trim()
            sent += command
            check(index < rows.size) { "Unexpected extra command $command" }
            val row = rows[index++]
            check(command == row.command) { "Row $index: expected ${row.command}, got $command" }
            if (index == failAt) throw IOException("Injected disconnect")
            bytes.addAll((row.raw + ">").toByteArray().map { it.toInt() and 255 })
        }
    }
    private fun rows(version: String): List<Row> = javaClass.getResourceAsStream("/replay/vehicle-$version-oem.tsv")!!
        .bufferedReader().useLines { lines -> lines.map {
            val parts = it.split('\t', limit = 2)
            Row(parts[0], String(Base64.getDecoder().decode(parts[1]), Charsets.UTF_8))
        }.toList() }

    @Test fun recorded033DtcQueueWithSyntheticPresenceAndObdRestoration() = runBlocking {
        replay("033", false)
    }
    @Test fun recorded034WithExplicitSyntheticMetadataSubstitution() = runBlocking {
        replay("034", true)
    }
    private suspend fun replay(version: String, metadata: Boolean) {
        val fixture = withSyntheticPresence(rows(version)).map {
            when {
                metadata && it.command == "1100021A9B" -> it.copy(command = "11000322F187", raw = it.raw.replace("7F1A11", "7F2231"))
                else -> it
            }
        }
        val link = Replay(fixture)
        val engine = ElmEngine(link, { _, _, _, _ -> })
        val units = linkedMapOf<String, ControlUnit>()
        val outcome = ElmVagScan.scan(engine, { units[it.address] = it }, { _, _, _ -> }, readGatewayIdentity = metadata)
        assertTrue(outcome.note, outcome.listConfirmed)
        assertEquals(18, units.size)
        assertEquals(15, units.values.count { it.state == ReadState.COMPLETE })
        assertEquals(listOf("01305", "01304"), units.getValue("VAG-19").dtcs.map { it.code })
        assertTrue(units.getValue("VAG-16").dtcs.isEmpty())
        assertTrue(units.getValue("VAG-16").state == ReadState.COMPLETE)
        assertFalse(outcome.complete)
        engine.connect()
        assertTrue(engine.vehicleReady)
        assertEquals(setOf("7E8", "7E9"), engine.observedCanResponderIds)
        assertEquals(fixture.size, link.index)
        assertFalse(link.sent.any { it.contains("1A9B") } && metadata)
    }

    private fun withSyntheticPresence(original: List<Row>): List<Row> {
        var rx = ""
        return original.flatMap { row ->
            if (row.command.startsWith("ATCRA")) rx = row.command.removePrefix("ATCRA")
            if (row.command != "1003") listOf(row)
            else if (row.raw.trim() == "NO DATA") listOf(row.copy(command = "3E00"))
            else {
                check(row.raw.contains("5003")) { "Unknown recorded session response" }
                listOf(Row("3E00", rx + "027E00"), row)
            }
        }
    }

    @Test fun recordedQueueDisconnectPreservesEarlierDtcAndStopsTraffic() = runBlocking {
        val fixture = withSyntheticPresence(rows("034"))
        val stop = fixture.indexOfFirst { it.command == "24C000100003011" } + 1
        assertTrue(stop > 0)
        val link = Replay(fixture, stop)
        val engine = ElmEngine(link, { _, _, _, _ -> })
        val units = linkedMapOf<String, ControlUnit>()
        val error = runCatching { ElmVagScan.scan(engine, { units[it.address] = it }, { _, _, _ -> }) }.exceptionOrNull()
        assertTrue(error.toString(), error is IOException)
        assertEquals(stop, link.index)
        assertEquals("24C000100003011", link.sent.last())
        assertEquals(2, units.getValue("VAG-19").dtcs.size)
        assertEquals(ReadState.COMPLETE, units.getValue("VAG-25").state)
    }
}
