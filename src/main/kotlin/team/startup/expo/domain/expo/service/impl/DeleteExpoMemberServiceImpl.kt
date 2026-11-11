package team.startup.expo.domain.expo.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import team.startup.expo.domain.expo.repository.ExpoMemberRepository
import team.startup.expo.domain.expo.service.DeleteExpoMemberService
import team.startup.expo.domain.expo.service.ExpoMemberSupport
import team.startup.expo.domain.expo.service.ExpoRoleSupport

@Service
class DeleteExpoMemberServiceImpl(
    private val roles: ExpoRoleSupport,
    private val members: ExpoMemberRepository,
    private val support: ExpoMemberSupport,
    transactions: PlatformTransactionManager,
) : DeleteExpoMemberService {
    private val writes = TransactionTemplate(transactions)

    override fun execute(
        expoId: String,
        adminId: Long,
    ) {
        val admin = roles.current()
        writes.executeWithoutResult {
            support.lockOwner(expoId, admin)
            val member = support.member(expoId, adminId)
            support.validateChange(expoId, member.role, null)
            members.delete(member)
        }
    }
}
