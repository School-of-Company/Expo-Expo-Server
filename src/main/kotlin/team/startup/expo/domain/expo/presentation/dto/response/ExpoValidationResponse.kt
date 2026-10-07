package team.startup.expo.domain.expo.presentation.dto.response

data class ExpoValidationResponse(
    val expoValid: List<ExpoValidResponse>,
)

data class ExpoValidResponse(
    val expoId: String,
    val preStandardFormCreatedStatus: Boolean,
    val siteStandardFormCreatedStatus: Boolean,
    val traineeFormCreatedStatus: Boolean,
    val standardSurveyCreatedStatus: Boolean,
    val traineeSurveyCreatedStatus: Boolean,
)
