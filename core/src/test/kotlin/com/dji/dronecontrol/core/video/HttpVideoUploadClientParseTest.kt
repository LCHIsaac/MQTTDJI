package com.dji.dronecontrol.core.video

import com.dji.dronecontrol.core.photo.PhotoErrorCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpVideoUploadClientParseTest {

    private val client = HttpVideoUploadClient()

    @Test
    fun parseSuccess_usesDownloadUrlVerbatim() {
        val json = """
            {
              "id": "vid-1",
              "download_url": "https://tronico.kujuhk.com/live/video/vid-1",
              "expires_at": "2026-10-10T12:00:00Z",
              "bytes": 999
            }
        """.trimIndent()
        val result = client.parseSuccess(json)
        assertTrue(result is VideoUploadResult.Success)
        assertEquals(
            "https://tronico.kujuhk.com/live/video/vid-1",
            (result as VideoUploadResult.Success).downloadUrl,
        )
    }

    @Test
    fun parseSuccess_emptyUrlFails() {
        val result = client.parseSuccess("""{"id":"x","download_url":"","bytes":1}""")
        assertTrue(result is VideoUploadResult.Failure)
        assertEquals(PhotoErrorCodes.UPLOAD_FAILED, (result as VideoUploadResult.Failure).code)
    }
}
