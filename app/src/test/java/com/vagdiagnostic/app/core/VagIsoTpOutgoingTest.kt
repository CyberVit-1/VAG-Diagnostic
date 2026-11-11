package com.vagdiagnostic.app.core

import org.junit.Assert.*
import org.junit.Test

/** Synthetic framing vectors, not vehicle replies. */
class VagIsoTpOutgoingTest {
    @Test fun shortReadsUsePciAndAaPadding() {
        assertEquals(listOf("023E00AAAAAAAAAA"), hex(VagIsoTpOutgoing.frames(listOf(0x3E, 0))))
        assertEquals(listOf("031902AFAAAAAAAA"), hex(VagIsoTpOutgoing.frames(listOf(0x19, 2, 0xAF))))
    }
    @Test fun sevenBytesFitOneFrameAndEightRequireFirstAndConsecutive() {
        assertEquals(listOf("0700010203040506"), hex(VagIsoTpOutgoing.frames((0..6).toList())))
        assertEquals(listOf("1008000102030405", "210607AAAAAAAAAA"), hex(VagIsoTpOutgoing.frames((0..7).toList())))
    }
    @Test fun consecutiveSequenceWrapsWithoutChangingPayload() {
        val input = List(200) { it and 255 }
        val frames = VagIsoTpOutgoing.frames(input)
        assertEquals(0x2F, frames[15][0])
        assertEquals(0x20, frames[16][0])
        val restored = frames.first().drop(2) + frames.drop(1).flatMap { it.drop(1) }
        assertEquals(input, restored.take(input.size))
        assertTrue(frames.all { it.size == 8 })
    }
    @Test fun maximumTwelveBitLengthIsEncodedExactly() {
        val frames = VagIsoTpOutgoing.frames(List(4095) { 0x22 })
        assertEquals(listOf(0x1F, 0xFF), frames.first().take(2))
        assertEquals(586, frames.size)
    }
    @Test fun invalidLengthAndByteCannotBeFramed() {
        for (input in listOf(emptyList(), List(4096) { 0 }, listOf(-1), listOf(256)))
            assertTrue(runCatching { VagIsoTpOutgoing.frames(input) }.exceptionOrNull() is IllegalArgumentException)
    }
    private fun hex(frames: List<List<Int>>) = frames.map { row -> row.joinToString("") { "%02X".format(it) } }
}
