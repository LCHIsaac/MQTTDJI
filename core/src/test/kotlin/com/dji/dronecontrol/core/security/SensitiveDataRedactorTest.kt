package com.dji.dronecontrol.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveDataRedactorTest {

    @Test
    fun redactsTokenInJson() {
        val raw = """{"cmd":"stream.start","params":{"channel":"ch1","token":"secret-token-xyz"}}"""
        val redacted = SensitiveDataRedactor.redactJsonPayload(raw)
        assertTrue(redacted.contains("\"token\":\"***\""))
        assertFalse(redacted.contains("secret-token-xyz"))
    }

    @Test
    fun ackAndStatusMustNotContainRawTokenAfterRedaction() {
        val ack = """{"type":"ack","msg_id":"1","ok":true,"result":{"channel":"c","viewer_url":"https://x/live?channel=c","token":"should-not-happen"}}"""
        val redacted = SensitiveDataRedactor.redactJsonPayload(ack)
        assertFalse(redacted.contains("should-not-happen"))
    }

    @Test
    fun redactsStreamTokenSecretFieldInJson() {
        val raw = """{"stream_token_secret":"super-secret-bearer"}"""
        val redacted = SensitiveDataRedactor.redactJsonPayload(raw)
        assertTrue(redacted.contains("\"stream_token_secret\":\"***\""))
        assertFalse(redacted.contains("super-secret-bearer"))
    }

    @Test
    fun redactForLogs_stripsAudienceTokenFromViewerUrlFragment() {
        val ack = """{"type":"ack","ok":true,"result":{"viewer_url":"https://x/live?channel=c#token=secret-audience-jwt"}}"""
        val forLogs = SensitiveDataRedactor.redactForLogs(ack)
        assertTrue(forLogs.contains("#token=***"))
        assertFalse(forLogs.contains("secret-audience-jwt"))
    }

    @Test
    fun redactsAuthorizationBearerInPlainText() {
        val raw = "Request failed: Authorization: Bearer my-secret-token-value"
        val redacted = SensitiveDataRedactor.redactPlainText(raw)
        assertTrue(redacted.contains("Authorization: Bearer ***"))
        assertFalse(redacted.contains("my-secret-token-value"))
    }
}
