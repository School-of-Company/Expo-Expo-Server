package team.startup.expo.domain.expo.service

import org.springframework.dao.DataAccessException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.global.exception.ExpectedException
import team.startup.expo.global.security.SecurityConfig

@Component
class AdminAccessSupport(
    private val decoder: JwtDecoder,
    private val authority: AdminAuthorityClient,
    private val expos: ExpoRepository,
    transactionManager: PlatformTransactionManager,
) {
    private val reads = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    fun currentAdminId(): Long? =
        (SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken)?.token?.subject?.toLongOrNull()

    fun currentName(): String? = SecurityContextHolder.getContext().authentication?.name

    fun authenticate(authorization: String?): Long {
        val jwt = decode(authorization)
        val adminId =
            jwt.subject?.toLongOrNull()?.takeIf { it > 0 }
                ?: throw ExpectedException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.")
        if (jwt.getClaimAsString("role") != SecurityConfig.ADMIN_AUTHORITY) {
            throw ExpectedException(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.")
        }
        authority.requireCurrentAdmin(requireNotNull(authorization))
        return adminId
    }

    fun decode(authorization: String?): Jwt {
        val token =
            authorization?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() }
                ?: throw ExpectedException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.")
        return try {
            decoder.decode(token)
        } catch (exception: JwtException) {
            throw ExpectedException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.")
        }
    }

    fun requireGrant(
        expoId: String,
        message: String,
        granted: () -> Boolean,
    ) {
        try {
            reads.executeWithoutResult {
                val expo =
                    expos.findByIdOrNull(expoId)
                        ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
                if (expo.deletingAt != null) throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "삭제 중인 박람회입니다.")
                if (!granted()) throw ExpectedException(HttpStatus.FORBIDDEN, message)
            }
        } catch (exception: DataAccessException) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "행사 권한을 확인할 수 없습니다.")
        }
    }
}
