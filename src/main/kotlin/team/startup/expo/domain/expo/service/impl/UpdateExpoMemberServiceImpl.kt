package team.startup.expo.domain.expo.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import team.startup.expo.domain.expo.presentation.dto.request.UpdateExpoMemberRequest
import team.startup.expo.domain.expo.presentation.dto.response.ExpoMemberResponse
import team.startup.expo.domain.expo.service.ExpoMemberSupport
import team.startup.expo.domain.expo.service.ExpoRoleSupport
import team.startup.expo.domain.expo.service.UpdateExpoMemberService

@Service
class UpdateExpoMemberServiceImpl(
    private val roles: ExpoRoleSupport,
    private val support: ExpoMemberSupport,
    transactions: PlatformTransactionManager,
) : UpdateExpoMemberService {
    private val writes = TransactionTemplate(transactions)

    override fun execute(
        expoId: String,
        adminId: Long,
        request: UpdateExpoMemberRequest,
    ): ExpoMemberResponse {
        val admin = roles.current()
        return requireNotNull(
            writes.execute {
                support.lockOwner(expoId, admin)
                val member = support.member(expoId, adminId)
                support.validateChange(expoId, member.role, request.role)
                member.role = request.role
                ExpoMemberResponse(member.adminId, member.role)
            },
        )
    }
}
