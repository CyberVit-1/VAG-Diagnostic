package com.vagdiagnostic.app.core

/** NO DATA, incomplete frames and unsupported modes never mean "no faults". */
enum class ReadState { NOT_QUERIED, COMPLETE, PARTIAL, NO_RESPONSE, FAILED }
enum class DtcKind(val command: String, val replySid: Int, val title: String) {
    CONFIRMED("03", 0x43, "Подтверждённая"),
    PENDING("07", 0x47, "Ожидающая"),
    PERMANENT("0A", 0x4A, "Постоянная")
}
data class Dtc(val raw: String, val code: String, val kind: DtcKind, val description: String? = null)
data class ControlUnit(
    val address: String,
    val name: String,
    val state: ReadState,
    val dtcs: List<Dtc> = emptyList(),
    val note: String = "",
    val answeredModes: Set<DtcKind> = emptySet()
)
data class FrameParse(val messages: Map<String, List<ByteArray>>, val problems: List<String>) {
    fun messagesFor(sid: Int): Map<String, List<ByteArray>> = messages.mapValues { (_, frames) ->
        frames.filter { it.isNotEmpty() && (it[0].toInt() and 0xFF) == sid }
    }.filterValues { it.isNotEmpty() }
}

/** Defensive ELM327 text decoder, limited to complete OBD/CAN frames. */
object ElmFrames {
    private val ecuHeader = Regex("^([0-9A-F]{3}|[0-9A-F]{8})\\s+(.+)$")
    private val byteToken = Regex("^[0-9A-F]{2}$")
    private val errorTokens = listOf("NO DATA", "UNABLE TO CONNECT", "STOPPED", "BUFFER FULL", "CAN ERROR", "BUS ERROR", "RX ERROR", "?")

    fun parse(command: String, raw: String): FrameParse {
        val messages = linkedMapOf<String, MutableList<ByteArray>>()
        val problems = mutableListOf<String>()
        data class Acc(val expected: Int, val bytes: MutableList<Int>, var nextSeq: Int)
        val partial = mutableMapOf<String, Acc>()
        var lastAddress = "OBD-UNIDENTIFIED"
        var numbered = mutableMapOf<String, MutableList<Int>>()

        fun add(address: String, bytes: List<Int>) {
            if (bytes.isNotEmpty()) messages.getOrPut(address) { mutableListOf() }.add(bytes.map { it.toByte() }.toByteArray())
        }
        for (initial in raw.uppercase().replace('>', '\n').replace('\r', '\n').split('\n')) {
            var line = initial.trim().replace(Regex("\\s+"), " ")
            if (line.isEmpty() || line == command.uppercase() || line == "SEARCHING..." || line.startsWith("AT") || line.startsWith("ELM327")) continue
            if (errorTokens.any { line.contains(it) }) { problems.add(line); continue }
            val header = ecuHeader.matchEntire(line)
            var address = lastAddress
            if (header != null) {
                address = header.groupValues[1]
                line = header.groupValues[2]
                lastAddress = address
            }
            // In CAF1 mode the ELM sometimes prints numbered multiline chunks (0:, 1:, ...).
            val numberedMatch = Regex("^([0-9A-F]):\\s*(.*)$").matchEntire(line)
            if (numberedMatch != null) {
                val index = numberedMatch.groupValues[1].toInt(16)
                val chunk = numberedMatch.groupValues[2].split(' ').filter { it.isNotEmpty() }
                if (!chunk.all { byteToken.matches(it) }) { problems.add("Недопустимый фрагмент: $line"); continue }
                val accumulator = numbered.getOrPut(address) { mutableListOf() }
                if (index == 0) accumulator.clear()
                if (index != (accumulator.size + 6) / 7 && index != 0) {
                    // Sequence is adapter-dependent, so do not claim a complete response.
                    problems.add("Нумерация ISO-TP требует проверки: $line")
                }
                accumulator.addAll(chunk.map { it.toInt(16) })
                continue
            }
            val parts = line.split(' ').filter { it.isNotEmpty() }
            // ELM may print the overall response byte count as a 3-digit line.
            if (parts.size == 1 && parts[0].length == 3 && parts[0].all { it in "0123456789ABCDEF" }) continue
            if (!parts.all { byteToken.matches(it) }) { problems.add("Неизвестная строка адаптера: $line"); continue }
            val bytes = parts.map { it.toInt(16) }
            if (bytes.isEmpty()) continue
            val first = bytes[0]
            when {
                first <= 7 && bytes.size > 1 -> {
                    if (bytes.size - 1 < first) problems.add("Неполный Single Frame от $address")
                    else add(address, bytes.drop(1).take(first))
                }
                (first and 0xF0) == 0x10 && bytes.size >= 2 -> {
                    val total = ((first and 0x0F) shl 8) + bytes[1]
                    if (total !in 1..4095) { problems.add("Недопустимая длина ISO-TP"); continue }
                    val payload = bytes.drop(2).toMutableList()
                    if (payload.size >= total) add(address, payload.take(total))
                    else partial[address] = Acc(total, payload, 1)
                }
                (first and 0xF0) == 0x20 -> {
                    val a = partial[address]
                    if (a == null || (first and 15) != (a.nextSeq and 15)) {
                        problems.add("Пропущен/перепутан Consecutive Frame: $address"); partial.remove(address)
                    } else {
                        a.bytes.addAll(bytes.drop(1)); a.nextSeq++
                        if (a.bytes.size >= a.expected) {
                            add(address, a.bytes.take(a.expected)); partial.remove(address)
                        }
                    }
                }
                // Some adapters return an already-decoded OBD payload without PCI.
                first in listOf(0x41, 0x43, 0x47, 0x4A, 0x49, 0x59, 0x7F) -> add(address, bytes)
                else -> problems.add("Фрейм неподдерживаемого формата: $line")
            }
        }
        partial.forEach { (address, acc) -> problems.add("Неполный ISO-TP ответ $address: ${acc.bytes.size}/${acc.expected}") }
        numbered.forEach { (address, body) ->
            if (body.isNotEmpty()) {
                // Numbered CAF1 output is only accepted when it starts with a recognizable SID.
                if (body.first() in listOf(0x41, 0x43, 0x47, 0x4A, 0x49, 0x59)) add(address, body)
                else problems.add("Неопределённое начало номерного ответа: $address")
            }
        }
        return FrameParse(messages, problems)
    }
}

object ObdDecoder {
    fun codeOf(hi: Int, lo: Int): String {
        val group = "PCBU"[(hi ushr 6) and 3]
        return "$group${(hi ushr 4) and 3}${(hi and 15).toString(16).uppercase()}${(lo ushr 4).toString(16).uppercase()}${(lo and 15).toString(16).uppercase()}"
    }

    fun readDtc(message: ByteArray, kind: DtcKind, isCan: Boolean): List<Dtc> {
        require(message.isNotEmpty() && (message[0].toInt() and 255) == kind.replySid) { "Неверный SID" }
        val records: List<Int>
        if (isCan) {
            // ISO 15765-4 Mode 03/07/0A: number of DTCs precedes 16-bit DTC pairs.
            require(message.size >= 2) { "Нет количества DTC в CAN-ответе" }
            val count = message[1].toInt() and 255
            require(message.size >= 2 + count * 2) { "Неполный список DTC: указано $count" }
            require((2 + count * 2 until message.size).all { message[it].toInt() == 0 }) {
                "Непредвиденные данные после списка CAN DTC"
            }
            records = (0 until count).map { 2 + it * 2 }
        } else {
            // Legacy OBD response: 43 followed by pairs, zero pairs are padding.
            require((message.size - 1) % 2 == 0) { "Обрезанная пара DTC" }
            records = (1 until message.size step 2).toList()
        }
        return records.mapNotNull { index ->
            val hi = message[index].toInt() and 255
            val lo = message[index + 1].toInt() and 255
            if (hi == 0 && lo == 0) null else {
                val code = codeOf(hi, lo)
                Dtc("%02X%02X".format(hi, lo), code, kind, LocalDescriptions.find(code))
            }
        }
    }

    fun hasValidVehicleResponse(frames: FrameParse): Boolean = frames.messagesFor(0x41).values.flatten().any { msg ->
        msg.size >= 6 && msg[1].toInt() == 0
    }

    fun decodedMode(frames: FrameParse, kind: DtcKind, isCan: Boolean): Map<String, List<Dtc>> {
        return frames.messagesFor(kind.replySid).mapValues { (_, payloads) -> payloads.flatMap { readDtc(it, kind, isCan) } }
    }
}

object LocalDescriptions {
    // Small manually curated set; unknown codes intentionally stay unknown.
    private val descriptions = mapOf(
        "P0299" to "Давление наддува ниже требуемого",
        "P0300" to "Обнаружены случайные или множественные пропуски воспламенения",
        "P0301" to "Пропуски воспламенения в цилиндре 1",
        "P0302" to "Пропуски воспламенения в цилиндре 2",
        "P0420" to "Эффективность катализатора ниже порогового значения (ряд 1)",
        "P0171" to "Топливная смесь слишком бедная (ряд 1)"
    )
    fun find(code: String): String? = descriptions[code]
}

object UdsDecoder {
    /** UDS 19 02 status mask -> 59 02 supportedStatusMask [3-byte DTC, status]*. */
    data class UdsFault(val raw24: String, val statusMask: Int)
    fun readDtcByStatusMask(payload: ByteArray): List<UdsFault> {
        require(payload.size >= 3 && payload[0].toInt() and 255 == 0x59 && payload[1].toInt() and 255 == 2) { "Не 59 02" }
        require((payload.size - 3) % 4 == 0) { "Неполная запись UDS DTC" }
        return (3 until payload.size step 4).map { pos ->
            val raw = (0..2).joinToString("") { "%02X".format(payload[pos + it].toInt() and 255) }
            UdsFault(raw, payload[pos + 3].toInt() and 255)
        }
    }
}