package com.vagdiagnostic.app.transport

/** Read-only adapter identification. A recognized banner is still only a
 * candidate; the configuration helper independently checks feature replies. */
internal object ElmAdapterNativeProbe {
    suspend fun inspect(engine: ElmEngine): Boolean {
        check(engine.vehicleReady)
        val raw = engine.command("STI", 1500)
        val rows = raw.replace('>', '\n').replace('\r', '\n').split('\n')
            .filter { it.trim().uppercase() != "STI" }.joinToString("\n")
        val info = ElmNativeSegmentation.identify(rows) ?: run {
            val vt = engine.command("VTI", 1500)
            ElmNativeSegmentation.identifyVtAdapter(vt) ?: return false
        }
        engine.nativeSegmentation = ElmNativeSegmentation(info) { engine.command(it) }
        return true
    }
}
