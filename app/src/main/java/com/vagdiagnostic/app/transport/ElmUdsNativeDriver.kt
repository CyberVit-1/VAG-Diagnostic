package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.VagOemReadOnly
import com.vagdiagnostic.app.core.VagUdsNativeTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException

/** Addressed native requests expect one complete response message. Pending
 * replies switch to bounded raw reception without issuing another request. */
internal object ElmUdsNativeDriver {
    suspend fun read(engine: ElmEngine, route: VagOemReadOnly.Route, request: String, pendingMs: Long): List<Int>? {
        require(ElmUdsReadRequests.allowed(request))
        check(engine.vehicleReady)
        val mode = requireNotNull(engine.nativeSegmentation)
        check(mode.enabled)
        val wire = request + "1"
        try {
            val raw = engine.command(wire, 8500, deferJournal = true)
            ElmCompletedCanError.check(raw, wire)
            val rows = raw.uppercase().replace('>', '\n').replace('\r', '\n').split('\n')
                .map { it.filterNot(Char::isWhitespace) }.filter { it.isNotEmpty() && it != wire }
            if (rows == listOf("NODATA")) return null
            val result = VagUdsNativeTransaction.decode(rows.joinToString("\r"), requireNotNull(route.udsRx), request)
            result.payload?.let { return it }
            mode.disable()
            val final = ElmUdsPending.read(engine, route, request, null, pendingMs, result.pendingCount)
            if (!mode.enableRoute(route)) throw VagProtocolFailure("Не восстановлен формат native CAN после ожидания")
            mode.setResponseTimeout(2000)
            return final
        } catch (cancel: CancellationException) { engine.close(); throw cancel }
        catch (can: ElmCompletedCanError) { throw can }
        catch (failure: IOException) { engine.close(); throw failure }
        catch (failure: RuntimeException) { engine.close(); throw VagProtocolFailure("Повреждённый native UDS ответ", failure) }
        finally { withContext(NonCancellable) { engine.flushJournal() } }
    }
}
