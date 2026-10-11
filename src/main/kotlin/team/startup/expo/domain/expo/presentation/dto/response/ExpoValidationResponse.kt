package team.startup.expo.domain.expo.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema

data class ExpoValidationResponse(
    @field:Schema(description = "박람회별 폼·설문 생성 여부", example = "[]")
    val expoValid: List<ExpoValidResponse>,
)

data class ExpoValidResponse(
    @field:Schema(description = "박람회 식별자", example = "sample-expo")
    val expoId: String,
    @field:Schema(description = "사전등록 일반 폼 생성 여부", example = "false")
    val preStandardFormCreatedStatus: Boolean,
    @field:Schema(description = "현장 일반 폼 생성 여부", example = "false")
    val siteStandardFormCreatedStatus: Boolean,
    @field:Schema(description = "연수 폼 생성 여부", example = "false")
    val traineeFormCreatedStatus: Boolean,
    @field:Schema(description = "일반 설문 생성 여부", example = "false")
    val standardSurveyCreatedStatus: Boolean,
    @field:Schema(description = "연수 설문 생성 여부", example = "false")
    val traineeSurveyCreatedStatus: Boolean,
)
