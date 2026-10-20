package team.startup.expo.domain.expo.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema

data class ExpoDetailResponse(
    @field:Schema(description = "제목", example = "예시 박람회")
    val title: String,
    @field:Schema(description = "설명", example = "가상 행사 설명")
    val description: String,
    @field:Schema(description = "박람회 시작일", example = "2030-01-01")
    val startedDay: String,
    @field:Schema(description = "박람회 종료일", example = "2030-01-02")
    val finishedDay: String,
    @field:Schema(description = "행사 장소", example = "가상 전시장")
    val location: String,
    @field:Schema(description = "업로드한 대표 이미지 URL", example = "https://example.invalid/image/sample")
    val coverImage: String?,
    @field:Schema(description = "경도 문자열", example = "127.0000")
    val x: String,
    @field:Schema(description = "위도 문자열", example = "37.0000")
    val y: String,
    @field:Schema(description = "새 사전등록 회차의 교사·예비교사 정원 기본값. null이면 미설정", example = "300")
    val preregisterTeacherCapacity: Int? = null,
    @field:Schema(description = "새 사전등록 회차의 교사·예비교사 외 대상 정원 기본값. null이면 미설정", example = "1000")
    val preregisterNonTeacherCapacity: Int? = null,
    @field:Schema(description = "참가자 전화번호 인증 필요 여부", example = "true")
    val participantPhoneVerificationRequired: Boolean,
)
