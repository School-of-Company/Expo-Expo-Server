package team.startup.expo.domain.expo.presentation.dto.response

data class AdminApplicationCancelAccessResponse(
    val expoId: String,
    val adminId: Long,
    val permission: String = "APPLICATION_CANCEL",
    val policyVersion: String = "EXPO_ADMIN_APPLICATION_CANCEL_V1",
    val scope: String = "EXPO_ADMIN",
)
