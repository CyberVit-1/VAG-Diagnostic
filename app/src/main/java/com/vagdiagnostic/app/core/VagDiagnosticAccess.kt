package com.vagdiagnostic.app.core

/** Read the Gateway's diagnostic-filter status, without changing it. */
object VagDiagnosticAccess {
    const val REQUEST = "22539B"
    data class Status(val functionCode: Int, val active: Boolean)
    fun decode(reply: List<Int>?): Status? {
        if (reply == null) return null
        if (reply.firstOrNull() == 0x7F) {
            require(reply.size == 3 && reply[1] == 0x22 && reply[2] != 0x78) { "Незавершённый или чужой статус доступа" }
            return null
        }
        require(reply.take(3) == listOf(0x62, 0x53, 0x9B)) { "Статус доступа от другого DID" }
        val body = reply.drop(3)
        if (body.size < 2 || body[0] !in setOf(0, 1, 4) || body[1] !in 0..1) return null
        return Status(body[0], body[1] == 1)
    }
}
