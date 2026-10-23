import com.vagdiagnostic.app.core.*

/** Standalone parsing checks, NOT a substitute for real ELM327 vehicle tests. */
fun main() {
    val found = ElmFrames.parse("0100", "0100\rSEARCHING...\r7E8 06 41 00 BE 3F B8 13 00\r>")
    check(ObdDecoder.hasValidVehicleResponse(found))
    check(found.messagesFor(0x41).keys == setOf("7E8"))

    val one = ElmFrames.parse("03", "03\r7E8 04 43 01 02 99 00 00 00\r>")
    check(ObdDecoder.decodedMode(one, DtcKind.CONFIRMED, true)["7E8"]!!.single().code == "P0299")

    val empty = ElmFrames.parse("03", "7E8 02 43 00 00 00 00 00 00\r>")
    check(ObdDecoder.decodedMode(empty, DtcKind.CONFIRMED, true)["7E8"]!!.isEmpty())

    val multi = ElmFrames.parse("03", "7E8 10 0A 43 04 01 33 01 01\r7E8 21 02 99 04 20 00 00 00\r>")
    val dtcs = ObdDecoder.decodedMode(multi, DtcKind.CONFIRMED, true)["7E8"]!!
    check(dtcs.map { it.code } == listOf("P0133", "P0101", "P0299", "P0420"))
    check(multi.problems.isEmpty())

    val broken = ElmFrames.parse("03", "7E8 10 0A 43 04 01 33 01 01\r>")
    check(broken.problems.any { it.contains("Неполный") })
    check(broken.messagesFor(0x43).isEmpty())

    val noResponse = ElmFrames.parse("03", "NO DATA\r>")
    check(noResponse.messages.isEmpty() && noResponse.problems.isNotEmpty())

    val legacy = ElmFrames.parse("03", "43 01 33 00 00 00 00\r>")
    check(ObdDecoder.decodedMode(legacy, DtcKind.CONFIRMED, false)["OBD-UNIDENTIFIED"]!!.single().code == "P0133")
    check(runCatching { ObdDecoder.readDtc(byteArrayOf(0x43, 1, 2), DtcKind.CONFIRMED, true) }.isFailure)

    val uds = UdsDecoder.readDtcByStatusMask(byteArrayOf(0x59, 0x02, 0xFF.toByte(), 0x12, 0x34, 0x56, 0x08))
    check(uds.single().raw24 == "123456" && uds.single().statusMask == 8)
    check(runCatching { UdsDecoder.readDtcByStatusMask(byteArrayOf(0x59, 0x02, 0xFF.toByte(), 0x12)) }.isFailure)
    println("OK: 10 core checks (responses, OBD/CAN, ISO-TP, DTC, UDS, missing data)")
}