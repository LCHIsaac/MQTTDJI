package com.dji.dronecontrol.core.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpPhotoUploadClientParseTest {

    private val client = HttpPhotoUploadClient()

    @Test
    fun parseSuccess_usesDownloadUrlVerbatim() {
        val json = """
            {
              "id": "abc123",
              "download_url": "https://tronico.kujuhk.com/live/photo/abc123",
              "expires_at": "2026-10-10T12:00:00Z",
              "bytes": 2048
            }
        """.trimIndent()
        val result = client.parseSuccess(json)
        assertTrue(result is PhotoUploadResult.Success)
        val ok = result as PhotoUploadResult.Success
        assertEquals("https://tronico.kujuhk.com/live/photo/abc123", ok.downloadUrl)
        assertEquals(2048, ok.bytes)
    }

    @Test
    fun parseSuccess_emptyDownloadUrlFails() {
        val result = client.parseSuccess("""{"id":"x","download_url":"","bytes":1}""")
        assertTrue(result is PhotoUploadResult.Failure)
        assertEquals(PhotoErrorCodes.UPLOAD_FAILED, (result as PhotoUploadResult.Failure).code)
    }
}
