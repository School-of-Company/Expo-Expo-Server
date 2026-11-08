package team.startup.expo.domain.expo.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import team.startup.expo.domain.expo.entity.ExpoMember
import team.startup.expo.domain.expo.presentation.dto.request.CreateExpoMemberRequest
import team.startup.expo.domain.expo.presentation.dto.response.ExpoMemberResponse
import team.startup.expo.domain.expo.repository.ExpoMemberRepository
import team.startup.expo.domain.expo.service.CreateExpoMemberService
import team.startup.expo.domain.expo.service.ExpoMemberSupport
import team.startup.expo.domain.expo.service.ExpoRoleSupport
import team.startup.expo.global.exception.ExpectedException

@Service
class CreateExpoMemberServiceImpl(
    private val roles: ExpoRoleSupport,
    private val members: ExpoMemberRepository,
    private val support: ExpoMemberSupport,
    transactions: PlatformTransactionManager,
) : CreateExpoMemberService {
    private val writes = TransactionTemplate(transactions)

    override fun execute(
        expoId: String,
        request: CreateExpoMemberRequest,
    ): ExpoMemberResponse {
        val admin = roles.current()
        return requireNotNull(
            writes.execute {
                support.lockOwner(expoId, admin)
                if (members.findByExpoIdAndAdminId(expoId, request.adminId) !=
                    null
                ) {
                    throw ExpectedException(HttpStatus.CONFLICT, "이미 등록된 운영진입니다.")
                }
                support.validateChange(expoId, null, request.role)
                val member = members.save(ExpoMember(expoId, request.adminId, request.role))
                ExpoMemberResponse(member.adminId, member.role)
            },
        )
    }
}
