package team.startup.expo.global.common.id

import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

// UUIDv7 (RFC 9562): 48비트 unix millis + version 7 + variant 10
@Component
class ExpoIdGenerator {
    private val random = SecureRandom()

    fun generate(): String {
        val millis = Instant.now().toEpochMilli()
        val msb = (millis shl 16) or VERSION_7 or (random.nextLong() and RAND_A_MASK)
        val lsb = (random.nextLong() and RAND_B_MASK) or VARIANT_RFC

        return UUID(msb, lsb).toString()
    }

    private companion object {
        const val VERSION_7 = 0x7000L
        const val RAND_A_MASK = 0x0FFFL
        const val RAND_B_MASK = 0x3FFFFFFFFFFFFFFFL
        const val VARIANT_RFC = Long.MIN_VALUE
    }
}
