package com.vagdiagnostic.app.transport

/** Adapter receive windows, separate from host transaction and pending limits.
 * Presence uses the establishment window; ordinary reads use the confirmed
 * adapter family's long window even when native segmentation was declined. */
internal object ElmUdsRequestTiming {
    suspend fun configure(engine: ElmEngine, request: String) {
        require(ElmUdsReadRequests.allowed(request))
        val adapter = engine.nativeSegmentation
        val milliseconds = if (request == "3E00" || adapter == null) 1000 else 2000
        if (adapter?.setOptionalResponseTimeout(milliseconds) == true) return
        // Generic ELM timeout units are 4ms: 1000 / 4 = 0xFA.
        engine.configureSetting("ATSTFA")
    }
}
