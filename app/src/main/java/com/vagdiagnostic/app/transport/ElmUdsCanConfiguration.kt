package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.VagOemReadOnly
import java.io.IOException

/** One physical route, with automatic FC disabled until all headers and
 * framing modes have been positively acknowledged. */
internal object ElmUdsCanConfiguration {
    suspend fun configure(engine: ElmEngine, target: VagOemReadOnly.Route) {
        check(engine.vehicleReady)
        val tx = requireNotNull(target.udsTx)
        val rx = requireNotNull(target.udsRx)
        suspend fun ok(command: String) { engine.configureSetting(command) }
        ok(if (target.is29Bit) "ATSP7" else "ATSP6")
        ok("ATCFC0")
        if (engine.needsFlowControlReset) { ok("ATFCSM0"); engine.needsFlowControlReset = false }
        val native = engine.nativeSegmentation?.enableRoute(target) == true
        for (command in listOf("ATH1", "ATD0", if (native) "ATCAF1" else "ATCAF0", "ATV0", "ATCEA", "ATAT0")) ok(command)
        if (target.is29Bit) {
            ok("ATCP%02X".format(tx ushr 24)); ok("ATSH%06X".format(tx and 0xFFFFFF))
            ok("ATCRA%08X".format(rx))
            if (engine.command("ATDPN").trim().uppercase() !in setOf("7", "A7"))
                throw IOException("ELM не подтвердил CAN 7")
        } else { ok("ATSH%03X".format(tx)); ok("ATCRA%03X".format(rx)) }
        if (native) requireNotNull(engine.nativeSegmentation).setResponseTimeout(2000)
        else {
            ok("ATSTFF")
            ok((if (target.is29Bit) "ATFCSH%08X" else "ATFCSH%03X").format(tx))
            ok("ATFCSD300010"); ok("ATFCSM1")
            engine.needsFlowControlReset = true
        }
        ok("ATCFC1")
    }
}
