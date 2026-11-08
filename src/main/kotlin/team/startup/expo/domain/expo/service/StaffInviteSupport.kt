package team.startup.expo.domain.expo.service

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.startup.expo.domain.expo.entity.ExpoStaffInvite
import team.startup.expo.domain.expo.repository.ExpoStaffInviteRepository
import team.startup.expo.global.exception.ExpectedException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

@Component
class StaffInviteSupport(
    private val invites: ExpoStaffInviteRepository,
) {
    fun newSecret(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    fun hash(secret: String): String =
        MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8)).joinToString("") {
            "%02x".format(it)
        }

    fun requireValid(
        id: UUID,
        expoId: String,
    ): ExpoStaffInvite =
        invites.findByIdAndExpoId(id, expoId)?.takeIf { it.isValid() }
            ?: throw ExpectedException(HttpStatus.FORBIDDEN, "유효한 스태프 링크가 아닙니다.")
}
