package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.VagOemReadOnly
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

/** Conservative synthetic adapter variant: an actual SH resets the priority.
 * It tests robustness and the researched SH-before-CP order, not the user's hardware. */
class ElmCanHeaderPairTest {
    private class Link : ElmLink {
        override val label = "Synthetic coupled CAN header"
        val sent = mutableListOf<String>()
        private val bytes = ArrayDeque<Int>()
        private var protocol = "6"
        private var low = 0
        private var high = 0x18
        val header: Int get() = (high shl 24) or low
        override suspend fun open() {}
        override fun close() { bytes.clear() }
        override suspend fun readByte(): Int = bytes.pollFirst() ?: -2
        override suspend fun write(bytes: ByteArray) {
            val c = bytes.toString(Charsets.US_ASCII).trim(); sent += c
            if (c.startsWith("ATSP") && c.last() in "67") protocol = c.last().toString()
            if (c.startsWith("ATSH")) { low = c.drop(4).toInt(16); high = 0x18 }
            if (c.startsWith("ATCP")) high = c.drop(4).toInt(16)
            val reply = when(c) { "ATZ" -> "ELM327"; "ATDPN" -> protocol
                "0100" -> "7E8064100BE3EA813"; else -> "OK" }
            for (b in (reply + "\r>").toByteArray()) this.bytes.add(b.toInt() and 255)
        }
    }
    @Test fun initialAndChangedHeadersFinishWithCorrectPriority() = runBlocking {
        val link = Link(); val engine = ElmEngine(link, { _, _, _, _ -> }); engine.connect()
        val route = VagOemReadOnly.routes.getValue(0x46)
        for (tx in listOf(0x17FC008B, 0x17FC009C, 0x17FC008B)) {
            ElmUdsCanConfiguration.configure(engine, route.copy(udsTx = tx))
            assertEquals("Full CAN ID after acknowledged configuration", tx, link.header)
        }
    }
}
