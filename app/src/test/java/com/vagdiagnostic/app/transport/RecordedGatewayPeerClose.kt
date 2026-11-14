package com.vagdiagnostic.app.transport

/** Explicit synthetic control adaptation for historical client-close replays.
 * The production reader now reuses a healthy Gateway channel. To retain the
 * old replay's subsequent fresh channel, inject a peer close after its complete
 * NRC11 and remove only the redundant client disconnect. No recorded ECU/DTC
 * payload is replaced. This is not a physical test of the new sequence. */
internal fun withExplicitGatewayPeerClose(rows: List<Pair<String, String>>): List<Pair<String, String>> {
    val rejection = rows.indexOfFirst { it.first.endsWith("1A9F") }
    require(rejection >= 0 && rows[rejection].second.contains("7F1A11"))
    val nextClose = rows.drop(rejection + 1).indexOfFirst { it.first == "A80" }
    require(nextClose >= 0)
    val close = rejection + 1 + nextClose
    require(close > rejection + 1 && rows[close - 1].first.startsWith("ATSH"))
    require(rows.getOrNull(close + 1)?.first == "ATH1")
    require(rows.subList(rejection + 1, close - 1).all { it.first.matches(Regex("B[0-9A-F]0")) })
    return rows.mapIndexedNotNull { index, row ->
        when (index) {
            rejection -> row.first to (row.second + "\r300A8")
            close - 1, close -> null
            else -> row
        }
    }
}
