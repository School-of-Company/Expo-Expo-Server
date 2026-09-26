package team.startup.expo.global.common.id

import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.time.Instant

@Component
class ExpoIdGenerator {
    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(16)
        ByteBuffer
            .wrap(bytes)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(Instant.now().toEpochMilli())
            .putLong(random.nextLong())

        return buildString(36) {
            bytes.forEachIndexed { index, byte ->
                if (index == 6 || index == 8 || index == 10 || index == 12) {
                    append('-')
                }
                append(HEX_DIGITS[(byte.toInt() ushr 4) and 0x0F])
                append(HEX_DIGITS[byte.toInt() and 0x0F])
            }
        }
    }

    private companion object {
        const val HEX_DIGITS = "0123456789abcdef"
    }
}
