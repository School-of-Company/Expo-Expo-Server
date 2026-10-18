package team.startup.expo.domain.expo.presentation.dto.response

data class AdminApplicationPromoteAccessResponse(
    val expoId: String,
    val adminId: Long,
    val permission: String = "APPLICATION_PROMOTE",
    val policyVersion: String = "EXPO_ADMIN_APPLICATION_PROMOTE_V1",
    val scope: String = "EXPO_ADMIN",
)
