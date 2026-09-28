package com.ssytdlp.app.core

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.Serializable

@Serializable
data class PendingLogin(
    val origin: String,
    val verifier: String,
    val state: String,
    val createdAt: Long
)

data class AuthorizationCode(val origin: String, val code: String, val verifier: String)

object AuthProtocol {
    const val REDIRECT_URI = "com.ssytdlp.app:/oauth/callback"
    const val LOGIN_LIFETIME_MS = 10 * 60 * 1000L
    private val base64Url = Base64.getUrlEncoder().withoutPadding()
    private val random = SecureRandom()
    private val tokenPattern = Regex("[A-Za-z0-9_-]{43}")

    fun normalizeOrigin(input: String): String {
        val uri = runCatching { URI(input.trim()) }.getOrNull()
        require(uri != null && uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "Enter an HTTPS server address, such as https://music.example.com:8443."
        }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") && (uri.port == -1 || uri.port in 1..65535)) {
            "Use the server origin only, without a path, credentials, or query."
        }
        require(!uri.host.contains(':') && !Regex("[0-9.]+").matches(uri.host)) {
            "Passkeys require a hostname, not an IP address."
        }
        return URI("https", null, uri.host.lowercase(), if (uri.port == 443) -1 else uri.port, null, null, null).toASCIIString()
    }

    fun begin(origin: String, now: Long = System.currentTimeMillis()): PendingLogin = PendingLogin(
        origin = normalizeOrigin(origin),
        verifier = randomValue(),
        state = randomValue(),
        createdAt = now
    )

    fun challenge(verifier: String): String =
        base64Url.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(UTF_8)))

    fun loginUrl(pending: PendingLogin): String = "${pending.origin}/app-login?" + listOf(
        "redirect_uri" to REDIRECT_URI,
        "code_challenge" to challenge(pending.verifier),
        "code_challenge_method" to "S256",
        "state" to pending.state
    ).joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, UTF_8.name())}" }

    fun acceptCallback(rawUri: String, pending: PendingLogin?, now: Long = System.currentTimeMillis()): AuthorizationCode {
        require(pending != null) { "No sign-in is pending. Start a new passkey sign-in." }
        require(now >= pending.createdAt && now - pending.createdAt <= LOGIN_LIFETIME_MS) {
            "Sign-in expired. Start a new passkey sign-in."
        }
        val uri = runCatching { URI(rawUri) }.getOrNull()
        require(uri != null && uri.scheme == "com.ssytdlp.app" && uri.rawAuthority == null &&
            !uri.isOpaque && uri.rawPath == "/oauth/callback" && uri.rawFragment == null) {
            "Invalid sign-in callback."
        }
        val parameters = linkedMapOf<String, String>()
        for (part in uri.rawQuery.orEmpty().split('&')) {
            val pair = part.split('=', limit = 2)
            require(pair.size == 2) { "Invalid sign-in parameters." }
            val key = URLDecoder.decode(pair[0], UTF_8.name())
            val value = URLDecoder.decode(pair[1], UTF_8.name())
            require(key in setOf("code", "state") && parameters.put(key, value) == null) {
                "Unexpected or duplicate sign-in parameters."
            }
        }
        val state = parameters["state"].orEmpty()
        require(MessageDigest.isEqual(state.toByteArray(UTF_8), pending.state.toByteArray(UTF_8))) {
            "Sign-in state did not match. Return to the sign-in started on this device."
        }
        val code = parameters["code"].orEmpty()
        require(tokenPattern.matches(code)) { "Invalid authorization code." }
        return AuthorizationCode(normalizeOrigin(pending.origin), code, pending.verifier)
    }

    private fun randomValue(): String = base64Url.encodeToString(ByteArray(32).also(random::nextBytes))
}
