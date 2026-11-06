package com.vagdiagnostic.app.transport

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** One selected RFCOMM device, one opening operation, no device discovery. */
internal class SppSession(
    private val driver: Driver,
    private val connectTimeoutMs: Long = 30_000,
    private val settle: suspend (Long) -> Unit = { delay(it) },
) {
    enum class Mode { INSECURE, SECURE }
    interface Driver {
        fun cancelDiscovery()
        fun create(mode: Mode): Connection
    }
    interface Connection {
        suspend fun connect()
        fun input(): InputStream
        fun output(): OutputStream
        fun close()
    }
    private val lifecycle = Any()
    private var started = false
    private var closed = false
    private var opening: Job? = null
    private var connection: Connection? = null
    private var streams: Pair<InputStream, OutputStream>? = null

    init { require(connectTimeoutMs > 0) }

    suspend fun open(): Unit = coroutineScope {
        val owner = currentCoroutineContext()[Job]
        synchronized(lifecycle) {
            check(!started) { "SPP-подключение уже запускалось" }
            check(!closed) { "SPP закрыт" }
            started = true
            opening = owner
        }
        try {
            var connected: Connection? = null
            var failure: IOException? = null
            for (mode in Mode.entries) {
                currentCoroutineContext().ensureActive()
                ensureOpen()
                var candidate: Connection? = null
                try {
                    driver.cancelDiscovery()
                    val created = driver.create(mode)
                    candidate = created
                    synchronized(lifecycle) {
                        if (closed) throw IOException("SPP-подключение отменено")
                        connection = candidate
                    }
                    val completed = withTimeoutOrNull(connectTimeoutMs) { created.connect(); true } == true
                    if (!completed) throw IOException("Время подключения SPP истекло")
                    ensureOpen()
                    connected = candidate
                    break
                } catch (problem: IOException) {
                    failure = problem
                    synchronized(lifecycle) { if (connection === candidate) connection = null }
                    runCatching { candidate?.close() }
                }
            }
            val active = connected ?: throw (failure ?: IOException("Не удалось подключить SPP"))
            // A connected socket with broken streams is not another pairing attempt.
            val ready = active.input() to active.output()
            settle(2_000)
            currentCoroutineContext().ensureActive()
            synchronized(lifecycle) {
                if (closed || connection !== active) throw IOException("SPP-подключение отменено")
                streams = ready
                opening = null
            }
        } catch (problem: Throwable) {
            finishClose(cancelOpening = false)
            throw problem
        }
    }

    fun input(): InputStream = synchronized(lifecycle) { streams?.first ?: throw IOException("SPP закрыт") }
    fun output(): OutputStream = synchronized(lifecycle) { streams?.second ?: throw IOException("SPP закрыт") }
    private fun ensureOpen() = synchronized(lifecycle) { if (closed) throw IOException("SPP закрыт") }

    fun close() = finishClose(cancelOpening = true)

    private fun finishClose(cancelOpening: Boolean) {
        val old = synchronized(lifecycle) {
            closed = true
            streams = null
            (opening to connection).also { opening = null; connection = null }
        }
        if (cancelOpening) old.first?.cancel()
        runCatching { old.second?.close() }
    }
}
