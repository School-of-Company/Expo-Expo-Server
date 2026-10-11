package team.startup.expo.domain.expo.presentation.dto.response

data class AdminExpoAccessResponse(
    val expoId: String,
    val adminId: Long,
    val permission: String = "APPLICATION_READ",
    val policyVersion: String = POLICY_VERSION,
    val scope: String = "GLOBAL_ADMIN",
) {
    companion object {
        const val POLICY_VERSION = "GLOBAL_ADMIN_APPLICATION_READ_V1"
    }
}
