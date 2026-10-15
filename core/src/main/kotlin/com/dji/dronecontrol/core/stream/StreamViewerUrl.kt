package com.dji.dronecontrol.core.stream

import java.net.URLEncoder

object StreamViewerUrl {
    const val DEFAULT_BASE_URL = "https://tronico.kujuhk.com/live"

    fun build(baseUrl: String, channel: String): String {
        val base = baseUrl.trimEnd('/')
        val encoded = URLEncoder.encode(channel, Charsets.UTF_8.name())
        return "$base?channel=$encoded"
    }

    /** Watchable link: channel in query, audience token in URL fragment (not query). */
    fun buildWatchable(baseUrl: String, channel: String, audienceToken: String): String {
        val bare = build(baseUrl, channel)
        val token = audienceToken.trim()
        require(token.isNotEmpty()) { "audience token required" }
        return "$bare#token=$token"
    }
}
