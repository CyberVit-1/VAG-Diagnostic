package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.VagOemReadOnly
import com.vagdiagnostic.app.core.VagUdsNativeTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** Addressed native requests expect one complete response message. Pending
 * replies switch to bounded raw reception without issuing another request. */
internal object ElmUdsNativeDriver {
    private const val TRANSACTION_TIMEOUT_MS = 20000L
    private data class NativeReply(val payload: List<Int>?)
    suspend fun read(engine: ElmEngine, route: VagOemReadOnly.Route, request: String, pendingMs: Long,
        sessionProbe: Boolean = false): List<Int>? {
        require(ElmUdsReadRequests.allowed(request))
        require(!sessionProbe || request == "1003")
        check(engine.vehicleReady)
        val mode = requireNotNull(engine.nativeSegmentation)
        check(mode.enabled)
        val wire = request + "1"
        try {
            val reply = withTimeoutOrNull(TRANSACTION_TIMEOUT_MS) {
                val raw = engine.command(wire, TRANSACTION_TIMEOUT_MS, deferJournal = true)
                ElmCompletedCanError.check(raw, wire)
                val rows = raw.uppercase().replace('>', '\n').replace('\r', '\n').split('\n')
                    .map { it.filterNot(Char::isWhitespace) }.filter { it.isNotEmpty() && it != wire }
                if (rows == listOf("NODATA")) return@withTimeoutOrNull NativeReply(null)
                if (sessionProbe && rows == listOf("?")) return@withTimeoutOrNull NativeReply(null)
                val result = VagUdsNativeTransaction.decode(rows.joinToString("\r"), requireNotNull(route.udsRx), request)
                result.payload?.let { return@withTimeoutOrNull NativeReply(it) }
                mode.disable()
                val final = ElmUdsPending.read(engine, route, request, null, pendingMs, result.pendingCount)
                if (!mode.enableRoute(route)) throw VagProtocolFailure("Не восстановлен формат native CAN после ожидания")
                mode.setResponseTimeout(if (request == "3E00" || sessionProbe) 1000 else 2000)
                NativeReply(final)
            } ?: throw VagProtocolFailure("Не завершён native UDS ответ")
            return reply.payload
        } catch (cancel: CancellationException) { engine.close(); throw cancel }
        catch (can: ElmCompletedCanError) { throw can }
        catch (failure: IOException) { engine.close(); throw failure }
        catch (failure: RuntimeException) { engine.close(); throw VagProtocolFailure("Повреждённый native UDS ответ", failure) }
        finally { withContext(NonCancellable) { engine.flushJournal() } }
    }
}
