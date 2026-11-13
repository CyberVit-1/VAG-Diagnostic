package com.vagdiagnostic.app.transport

import org.junit.Assert.*
import org.junit.Test

class VagReplaySettingsTest {
    private fun setup(tx: String = "710", rx: String = "77A") =
        listOf("ATSP6", "ATH1", "ATD0", "ATCAF1", "ATCFC0", "ATV0", "ATCEA", "ATSTFA", "ATAT0",
            "ATSH$tx", "ATCRA$rx", "ATFCSH$tx", "ATFCSD300010", "ATFCSM1", "ATCFC1").map { it to "OK" }
    @Test fun adaptationPreservesAllVehicleTrafficIncludingPendingAndIncompletePayloads() {
        val data = listOf("3E001" to "77A027E00", "1902AF" to "77A037F1978\r77A100A5902AF12",
            "1902AF" to "NO DATA")
        val rows = setup() + data + setup("70E", "778") + data
        val result = withConfirmedAutomaticSettings(rows)
        assertEquals(rows.filterNot { it.first.startsWith("AT") }, result.filterNot { it.first.startsWith("AT") })
        assertEquals(1, result.count { it.first == "ATSP6" })
        assertEquals(listOf("ATSP6", "ATCFC0", "ATH1"), result.take(3).map { it.first })
        assertTrue(result.contains("ATCRA778" to "OK"))
        assertTrue(result.contains("ATFCSH70E" to "OK"))
    }
    @Test fun resetRestoresSettingsAndTpSetupIsNeverFiltered() {
        val tp = listOf("ATH1", "ATCAF0", "ATCFC0", "ATSP6", "ATD0", "ATV1").map { it to "OK" }
        val result = withConfirmedAutomaticSettings(tp + tp + setup() + listOf("ATZ" to "ELM327") + setup())
        assertEquals(tp + tp, result.take(tp.size * 2))
        assertEquals(setup().size, result.dropWhile { it.first != "ATZ" }.drop(1).size)
    }
    @Test fun refusedSettingsAreRetainedAndNeverTurnIntoSyntheticAcknowledgements() {
        val rows = setup().map { if (it.first == "ATCAF1") it.first to "?" else it } + setup()
        val result = withConfirmedAutomaticSettings(rows)
        assertEquals(listOf("?", "OK"), result.filter { it.first == "ATCAF1" }.map { it.second })
    }
}
