package com.ssytdlp.app.core

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class AuthProtocolTest {
    private val now = 1_000_000L
    private val pending = AuthProtocol.begin("https://music.example.com:8443", now)
    private val code = "A".repeat(43)
    private val callback = "${AuthProtocol.REDIRECT_URI}?code=$code&state=${pending.state}"

    @Test fun `PKCE matches the RFC 7636 S256 test vector`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            AuthProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        )
    }

    @Test fun `login URL uses server contract and never contains verifier`() {
        val url = AuthProtocol.loginUrl(pending)
        assertEquals("/app-login", URI(url).path)
        assertTrue(url.contains("redirect_uri=com.ssytdlp.app%3A%2Foauth%2Fcallback"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("state=${pending.state}"))
        assertFalse(url.contains(pending.verifier))
    }

    @Test fun `verifier and state are independent fresh base64url values`() {
        val next = AuthProtocol.begin(pending.origin, now)
        for (value in listOf(pending.verifier, pending.state, next.verifier, next.state)) {
            assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(value))
        }
        assertEquals(4, setOf(pending.verifier, pending.state, next.verifier, next.state).size)
    }

    @Test fun `callback returns only the saved origin and secret verifier`() {
        assertEquals(AuthorizationCode(pending.origin, code, pending.verifier), AuthProtocol.acceptCallback(callback, pending, now))
    }

    @Test fun `rejects unsolicited expired and clock rollback callbacks`() {
        assertThrows(IllegalArgumentException::class.java) { AuthProtocol.acceptCallback(callback, null, now) }
        assertThrows(IllegalArgumentException::class.java) {
            AuthProtocol.acceptCallback(callback, pending, now + AuthProtocol.LOGIN_LIFETIME_MS + 1)
        }
        assertThrows(IllegalArgumentException::class.java) { AuthProtocol.acceptCallback(callback, pending, now - 1) }
    }

    @Test fun `rejects altered origins paths fragments state and duplicates`() {
        val invalid = listOf(
            callback.replace("com.ssytdlp.app:", "other.app:"),
            callback.replace(":/oauth", "://host/oauth"),
            callback.replace("/oauth/callback", "/oauth/callback/"),
            callback.replace("/oauth/callback", "/oauth/%63allback"),
            callback + "#fragment",
            callback.replace(pending.state, "B".repeat(43)),
            callback + "&state=${pending.state}",
            callback + "&%73tate=${pending.state}",
            callback + "&code=$code",
            callback + "&origin=https%3A%2F%2Fattacker.example",
            callback.replace("code=$code", "code=short")
        )
        invalid.forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) { AuthProtocol.acceptCallback(value, pending, now) }
        }
    }

    @Test fun `accepts HTTPS hostname origins and canonicalizes default port`() {
        assertEquals("https://music.example.com", AuthProtocol.normalizeOrigin(" HTTPS://MUSIC.EXAMPLE.COM:443/ "))
        assertEquals(pending.origin, AuthProtocol.normalizeOrigin(pending.origin))
    }

    @Test fun `rejects insecure or ambiguous server addresses`() {
        listOf("http://music.example.com", "https://127.0.0.1", "https://[::1]", "https://user@music.example.com",
            "https://music.example.com/api", "https://music.example.com?redirect=bad", "https://music.example.com#bad",
            "https://music.example.com:0", "https://music.example.com:99999", "not a url").forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) { AuthProtocol.normalizeOrigin(value) }
        }
    }
}