package com.vagdiagnostic.app.core

import org.junit.Assert.*
import org.junit.Test

class VagKwpCapabilitiesTest {
    @Test fun saeRequiresAnAlignedAdvertisedFunctionPair() {
        assertEquals(true, VagKwpCapabilities.supportsSaeDtc(listOf(0x71, 0xB8, 0, 0x10, 1, 0x18)))
        assertEquals(false, VagKwpCapabilities.supportsSaeDtc(listOf(0x71, 0xB8, 0x10, 1, 0x18, 0)))
    }
    @Test fun NegativeAndMalformedBodiesDoNotProveSaeSupport() {
        for (r in listOf(listOf(0x7F, 0x31, 0x11), listOf(0x71, 0xB8), listOf(0x71, 0xB8, 1)))
            assertNull(VagKwpCapabilities.supportsSaeDtc(r))
    }
}
