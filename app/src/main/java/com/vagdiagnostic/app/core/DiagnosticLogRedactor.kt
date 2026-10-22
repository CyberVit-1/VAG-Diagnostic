package com.vagdiagnostic.app.core

/**
 * Protect VIN-like values in log exports without destroying diagnostic CAN frames.
 *
 * A packed 11-bit CAN frame has a 3-digit header followed by complete bytes.
 * A 7-byte CAN response therefore often has 17 hex characters: such a frame
 * must not be replaced merely because it also matches the length of a VIN.
 *
 * This intentionally does NOT hide raw diagnostic CAN bytes, which may carry
 * sensitive payloads: always review a log before sharing it publicly.
 */
object DiagnosticLogRedactor {
    private val vinToken = Regex("\\b[A-HJ-NPR-Z0-9]{17}\\b", RegexOption.IGNORE_CASE)
    private val packedCan11 = Regex("^[0-9A-F]{3}(?:[0-9A-F]{2}){3,}$")
    private val packedCan29 = Regex("^(?:18DA|18DB)[0-9A-F]{4}(?:[0-9A-F]{2}){3,}$")
    private val segment = Regex("[^\\r\\n]+")

    fun redact(raw: String): String = segment.replace(raw) { match ->
        val line = match.value
        val compact = line.filterNot { it.isWhitespace() }.uppercase()
        if (packedCan11.matches(compact) || packedCan29.matches(compact)) line
        else vinToken.replace(line, "[VIN REDACTED]")
    }
}
