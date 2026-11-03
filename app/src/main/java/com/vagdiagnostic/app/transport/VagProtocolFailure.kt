package com.vagdiagnostic.app.transport

import java.io.IOException

/** A diagnostic transaction cannot be reused, but a fresh verified transport
 * may run a bounded new command. Socket loss/cancellation are separate types. */
internal open class VagProtocolFailure(message: String, cause: Throwable? = null,
    val noData: Boolean = false) : IOException(message, cause)

internal class ElmCommandTimeout(message: String, cause: Throwable? = null) :
    VagProtocolFailure(message, cause, noData = true)
