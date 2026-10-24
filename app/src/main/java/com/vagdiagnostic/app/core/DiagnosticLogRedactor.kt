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

    /**
     * Mode 09 PID 02 and UDS DID F190 carry the VIN as HEX-encoded ASCII.
     * A plain 17-character VIN regex cannot protect such ISO-TP frames.
     * Hide the entire command payload in a shared TXT export; the unchanged
     * original remains in Room for local diagnostics. Never leak partial VIN.
     */
    fun redact(raw: String, command: String): String {
        val request = command.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }
        return if (request == "0902" || request == "22F190") {
            "[VIN-bearing diagnostic payload hidden in TXT export; original remains in local journal]"
        } else redact(raw)
    }

    fun redact(raw: String): String = segment.replace(raw) { match ->
        val line = match.value
        val compact = line.filterNot { it.isWhitespace() }.uppercase()
        if (packedCan11.matches(compact) || packedCan29.matches(compact)) line
        else vinToken.replace(line, "[VIN REDACTED]")
    }
}
