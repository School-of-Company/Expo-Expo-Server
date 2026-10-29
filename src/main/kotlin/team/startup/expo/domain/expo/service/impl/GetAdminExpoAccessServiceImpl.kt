package team.startup.expo.domain.expo.service.impl

import org.springframework.dao.DataAccessException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.stereotype.Service
import team.startup.expo.domain.expo.presentation.dto.response.AdminExpoAccessResponse
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.AdminAuthorityClient
import team.startup.expo.domain.expo.service.GetAdminExpoAccessService
import team.startup.expo.global.exception.ExpectedException

@Service
class GetAdminExpoAccessServiceImpl(
    private val decoder: JwtDecoder,
    private val authority: AdminAuthorityClient,
    private val expos: ExpoRepository,
) : GetAdminExpoAccessService {
    override fun execute(
        expoId: String,
        authorization: String?,
    ): AdminExpoAccessResponse {
        val token =
            authorization?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() }
                ?: throw ExpectedException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.")
        val jwt =
            try {
                decoder.decode(token)
            } catch (exception: JwtException) {
                throw ExpectedException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.")
            }
        val adminId =
            jwt.subject?.toLongOrNull()?.takeIf { it > 0 }
                ?: throw ExpectedException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.")
        if (jwt.getClaimAsString("role") != "ROLE_ADMIN") {
            throw ExpectedException(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.")
        }
        authority.requireCurrentAdmin(requireNotNull(authorization))
        val expo =
            try {
                expos.findByIdOrNull(expoId)
            } catch (exception: DataAccessException) {
                throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "행사 권한을 확인할 수 없습니다.")
            } ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
        if (expo.deletingAt != null) throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "삭제 중인 박람회입니다.")
        return AdminExpoAccessResponse(expoId, adminId)
    }
}
