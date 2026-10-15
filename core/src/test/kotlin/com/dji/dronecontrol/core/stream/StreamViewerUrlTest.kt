package com.dji.dronecontrol.core.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamViewerUrlTest {

    @Test
    fun buildWatchable_putsAudienceTokenInFragment() {
        val url = StreamViewerUrl.buildWatchable(
            StreamViewerUrl.DEFAULT_BASE_URL,
            "test-01",
            "audience-jwt-value",
        )
        assertEquals(
            "https://tronico.kujuhk.com/live?channel=test-01#token=audience-jwt-value",
            url,
        )
    }
}
