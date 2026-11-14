package com.vagdiagnostic.app.transport

import java.io.IOException

/** A standalone adapter error after a complete ELM prompt, not socket loss or
 * a response mixed with CAN payloads. Only this exact boundary is retryable. */
internal class ElmCompletedCanError : IOException("ELM завершил команду сообщением CAN ERROR") {
    companion object {
        fun check(raw: String, echo: String) {
            val rows = raw.uppercase().replace('>', '\n').replace('\r', '\n').split('\n')
                .map { it.filterNot(Char::isWhitespace) }.filter { it.isNotEmpty() && it != echo.uppercase() }
            if (rows == listOf("CANERROR")) throw ElmCompletedCanError()
        }
    }
}
