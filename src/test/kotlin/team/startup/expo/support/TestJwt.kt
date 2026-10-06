package team.startup.expo.support

import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64

object TestJwt {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    val publicKeyPem: String =
        "-----BEGIN PUBLIC KEY-----\n${Base64.getMimeEncoder(
            64,
            "\n".toByteArray(),
        ).encodeToString(pair.public.encoded)}\n-----END PUBLIC KEY-----"

    fun token(
        role: String,
        ttlSeconds: Long = 900,
        issuedAtOffsetSeconds: Long = 0,
    ): String {
        val now = Instant.now().epochSecond + issuedAtOffsetSeconds
        val header = encode("""{"alg":"RS256","typ":"JWT"}""")
        val payload = encode("""{"sub":"1","role":"$role","iat":$now,"exp":${now + ttlSeconds}}""")
        val content = "$header.$payload"
        val signature =
            Signature
                .getInstance("SHA256withRSA")
                .apply {
                    initSign(pair.private)
                    update(content.toByteArray(StandardCharsets.UTF_8))
                }.sign()
        return "$content.${encoder.encodeToString(signature)}"
    }

    private fun encode(value: String): String = encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}
