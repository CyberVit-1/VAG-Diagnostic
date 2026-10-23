package com.vagdiagnostic.app.core

/**
 * Preparation for physically addressed, read-only CAN diagnostics.
 * This module NEVER transmits to an ECU. A verified route, ELM CAN filter,
 * and addressed ISO-TP flow control are required before integration.
 */
object VagAddressedCan {
    data class AdapterEvidence(
        val confirmed11Bit500k: Boolean = false,
        val addressedFilterValidated: Boolean = false,
        val addressedFlowControlValidated: Boolean = false
    )

    data class Plan(
        val setup: List<String>,
        val request: String,
        val expectedResponseCanId: Int,
        /** OBD must reconnect with normal configuration after any addressed session. */
        val mustReinitializeObdAfterwards: Boolean = true
    )

    sealed class PlanResult {
        data class Blocked(val reason: String) : PlanResult()
        data class Prepared(val plan: Plan) : PlanResult()
    }

    /** Offline plan; no AT or UDS requests are sent here. */
    fun prepareGatewayDtcRead(
        connected: Boolean,
        elmProtocol: String,
        profile: VagGatewayReadiness.VerifiedProfile?,
        evidence: AdapterEvidence
    ): PlanResult {
        if (!evidence.confirmed11Bit500k || elmProtocol !in setOf("6", "A6"))
            return PlanResult.Blocked("Не подтверждён 11-битный CAN 500 кбит/с")
        val gate = VagGatewayReadiness.evaluate(
            connected, true, profile, evidence.addressedFlowControlValidated
        )
        if (gate is VagGatewayReadiness.Decision.Blocked) return PlanResult.Blocked(gate.reason)
        if (!evidence.addressedFilterValidated)
            return PlanResult.Blocked("Адресный CAN фильтр адаптера не проверен")
        val route = (gate as VagGatewayReadiness.Decision.EligibleForImplementation).profile
        return PlanResult.Prepared(
            Plan(
                setup = listOf("ATCAF1", "ATCFC1",
                    "ATSH%03X".format(route.diagnosticRequestCanId),
                    "ATCRA%03X".format(route.diagnosticResponseCanId)),
                request = "1902FF",
                expectedResponseCanId = route.diagnosticResponseCanId
            )
        )
    }

    data class Reply(val payload: ByteArray?, val problems: List<String>) {
        val valid: Boolean get() = payload != null && problems.isEmpty()
    }

    /** Strict single-ECU ELM transcript decoder; never attributes headerless data to an ECU. */
    fun decodeSingleEcu(raw: String, expectedCanId: Int): Reply {
        if (expectedCanId !in 0..0x1FFFFFFF)
            return Reply(null, listOf("Недопустимый CAN ID"))
        val header = if (expectedCanId <= 0x7FF) "%03X".format(expectedCanId)
                     else "%08X".format(expectedCanId)
        val errors = mutableListOf<String>()
        val pciFrames = mutableListOf<List<Int>>()
        val cafBytes = mutableListOf<Int>()
        var expectedCafLength: Int? = null
        var cafNumber = 0
        var usedCaf = false
        var lastHeaderOk = false
        val headerPattern = Regex("^([0-9A-F]{3}|[0-9A-F]{8})\\s+(.+)$")
        val numberedPattern = Regex("^([0-9A-F]):\\s*(.*)$")
        val hex = Regex("^[0-9A-F]+$")
        val adapterErrors = listOf("NO DATA", "UNABLE TO CONNECT", "STOPPED",
            "BUFFER FULL", "CAN ERROR", "BUS ERROR", "RX ERROR")
        for (rawLine in raw.uppercase().replace('>', '\n').replace('\r', '\n').split('\n')) {
            val line = rawLine.trim().replace(Regex("\\s+"), " ")
            if (line.isEmpty() || line == "SEARCHING..." || line == "1902FF") continue
            if (adapterErrors.any { it in line }) {
                errors += "ELM: " + line
                continue
            }
            // ELM CAF1 numbered stream has an overall hexadecimal byte count.
            if (line.length == 3 && hex.matches(line)) {
                if (expectedCafLength != null) errors += "Повторный размер CAF1"
                else expectedCafLength = line.toInt(16)
                continue
            }
            var bytesText = line
            var thisHeaderOk = false
            val spaced = headerPattern.matchEntire(line)
            if (spaced != null) {
                if (spaced.groupValues[1] != header) {
                    errors += "Посторонний CAN ID: " + spaced.groupValues[1]
                    continue
                }
                bytesText = spaced.groupValues[2]
                thisHeaderOk = true
            } else if (line.startsWith(header) && !line.contains(' ') &&
                       line.length > header.length) {
                bytesText = line.drop(header.length)
                thisHeaderOk = true
            }
            val numbered = numberedPattern.matchEntire(bytesText)
            if (numbered != null) {
                if (!thisHeaderOk && !lastHeaderOk) {
                    errors += "CAF1 без подтверждённого адреса ЭБУ"
                    continue
                }
                val index = numbered.groupValues[1].toInt(16)
                if (index != cafNumber || cafNumber >= 16) {
                    errors += "Неверный номер CAF1: " + index
                    continue
                }
                val packed = numbered.groupValues[2].replace(" ", "")
                if (packed.isEmpty() || packed.length % 2 != 0 || !hex.matches(packed)) {
                    errors += "Некорректные байты CAF1"
                    continue
                }
                cafBytes.addAll(packed.chunked(2).map { it.toInt(16) })
                cafNumber++
                usedCaf = true
                lastHeaderOk = true
                continue
            }
            if (!thisHeaderOk) {
                errors += "Ответ без ожидаемого CAN ID: " + line
                continue
            }
            lastHeaderOk = true
            val packed = bytesText.replace(" ", "")
            if (packed.isEmpty() || packed.length % 2 != 0 || !hex.matches(packed)) {
                errors += "Некорректные байты ISO-TP"
                continue
            }
            pciFrames += packed.chunked(2).map { it.toInt(16) }
        }

        if (usedCaf || expectedCafLength != null) {
            if (pciFrames.isNotEmpty()) errors += "Смешаны номерные и PCI кадры"
            val size = expectedCafLength
            if (!usedCaf || size == null || size !in 1..4095) errors += "Нет длины CAF1"
            else if (cafBytes.size < size) errors += "Обрезанный CAF1: " + cafBytes.size + "/" + size
            else if (cafBytes.size > size && cafBytes.drop(size).any { it != 0 })
                errors += "Данные за концом CAF1"
            if (errors.isNotEmpty()) return Reply(null, errors)
            return Reply(cafBytes.take(size!!).map { it.toByte() }.toByteArray(), emptyList())
        }
        if (pciFrames.isEmpty()) errors += "Не найден ответ от ожидаемого CAN ID " + header
        if (errors.isNotEmpty()) return Reply(null, errors)
        val first = pciFrames.first()
        val pci = first[0]
        if (pci in 1..7) {
            if (pciFrames.size != 1) return Reply(null, listOf("Лишние кадры после SF"))
            if (first.size - 1 < pci) return Reply(null, listOf("Обрезан Single Frame"))
            return Reply(first.drop(1).take(pci).map { it.toByte() }.toByteArray(), emptyList())
        }
        if ((pci and 0xF0) != 0x10 || first.size < 2)
            return Reply(null, listOf("Ожидался ISO-TP Single Frame или First Frame"))
        val length = ((pci and 0x0F) shl 8) or first[1]
        if (length !in 8..4095) return Reply(null, listOf("Некорректная длина FF: " + length))
        val collected = first.drop(2).toMutableList()
        var nextSeq = 1
        for (i in 1 until pciFrames.size) {
            if (collected.size >= length) {
                errors += "Кадр после завершения ISO-TP"
                break
            }
            val fragment = pciFrames[i]
            if (fragment.isEmpty() || (fragment[0] and 0xF0) != 0x20 ||
                (fragment[0] and 0x0F) != nextSeq) {
                errors += "Неверный номер Consecutive Frame"
                break
            }
            collected.addAll(fragment.drop(1))
            nextSeq = (nextSeq + 1) and 15
        }
        if (collected.size < length) errors += "Неполный ISO-TP: " + collected.size + "/" + length
        if (errors.isNotEmpty()) return Reply(null, errors)
        return Reply(collected.take(length).map { it.toByte() }.toByteArray(), emptyList())
    }
}
