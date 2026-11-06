package com.vagdiagnostic.app.transport

/** Pure byte-stream transport contract. A read is never assumed to return a whole ELM response. */
interface ElmLink {
    val label: String
    /** BLE notification readiness alone does not confirm the adapter UART. */
    val needsUartReadinessProbe: Boolean get() = false
    suspend fun open()
    suspend fun write(bytes: ByteArray)
    /** A byte 0..255, -2 = no byte yet, -1 = peer closed the stream. */
    suspend fun readByte(): Int
    fun close()
}
