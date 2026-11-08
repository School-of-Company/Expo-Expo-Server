package team.startup.expo.domain.expo.service

interface DeleteExpoMemberService {
    fun execute(
        expoId: String,
        adminId: Long,
    ): Unit
}
