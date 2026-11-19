package team.startup.expo.domain.expo.presentation.dto.response

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import team.startup.expo.domain.expo.service.ExpoFeatureGroup

data class ExpoFeatureSettingsResponse(
    val expoId: String,
    @field:Schema(description = "기능 설정 revision. 저장할 때 expectedRevision으로 보냅니다.")
    val revision: Long,
    val groups: List<ExpoFeatureGroupResponse>,
    val features: List<ExpoFeatureResponse>,
    @field:Schema(description = "기존 전화번호 인증 설정. 기능 행에 복제하지 않습니다.")
    val authentication: ParticipantPhoneVerificationResponse,
    @field:Schema(description = "저장 응답에만 있습니다.", nullable = true)
    val applied: ExpoFeatureAppliedResponse? = null,
)

// 내부·공개 조회와 이벤트 snapshot이 함께 쓰는 기능 상태 한 줄. 카탈로그 순서로 모든 키(공개는 참가자용 키만)를 싣는다.
// 공개 조회는 configuredEnabled를 생략한다(null이면 JSON에서 빠진다).
data class ExpoFeatureStateResponse(
    @field:Schema(description = "기능 키", example = "SURVEY")
    val key: String,
    @field:Schema(description = "운영자가 고른 값. 공개 조회에는 없습니다.", nullable = true)
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val configuredEnabled: Boolean? = null,
    @field:Schema(description = "선행 기능까지 반영한 실제 사용 가능 여부. 정책 검사는 이 값만 씁니다.", example = "true")
    val effectiveEnabled: Boolean,
)

data class ExpoFeatureGroupResponse(
    val key: ExpoFeatureGroup,
    val label: String,
)

data class ExpoFeatureResponse(
    val key: String,
    val label: String,
    val description: String,
    val group: ExpoFeatureGroup,
    val order: Int,
    val requires: List<String>,
    @field:Schema(description = "운영자가 고른 값")
    val configuredEnabled: Boolean,
    @field:Schema(description = "선행 기능까지 반영한 실제 사용 가능 여부")
    val effectiveEnabled: Boolean,
    @field:Schema(description = "실효 값을 OFF로 만든 선행 기능")
    val blockedBy: List<String>,
    val readiness: ExpoFeatureReadinessResponse,
    @field:Schema(description = "STANDARD_PROGRAM·TRAINING_PROGRAM에만 있습니다. 회차별 준비 상태와 준비된 회차 수", nullable = true)
    val programReadiness: ExpoProgramReadinessResponse? = null,
)

// READY: Expo가 확인할 수 있는 준비 조건을 모두 갖췄다. NEEDS_SETUP: 운영자가 설정을 더 해야 한다.
// UNAVAILABLE: 진행 중인 변경 등으로 지금은 확인·사용할 수 없다. UNKNOWN: 외부 조회 실패로 판단하지 못했다(OFF나 설정 없음으로 읽지 않는다).
enum class ExpoFeatureReadiness { READY, NEEDS_SETUP, UNAVAILABLE, UNKNOWN }

// 회차 모집 단계. 정보용이며 준비 상태를 바꾸지 않는다(모집 전·후라는 이유로 NEEDS_SETUP이 되지 않는다).
enum class ProgramRecruitmentPhase { NO_POLICY, UPCOMING, OPEN, ENDED, CLOSED }

data class ExpoProgramReadinessResponse(
    val totalSessions: Int,
    val readySessions: Int,
    val sessions: List<ExpoProgramSessionReadinessResponse>,
)

data class ExpoProgramSessionReadinessResponse(
    val programId: Long,
    val title: String,
    val status: ExpoFeatureReadiness,
    val reasons: List<ExpoFeatureReadinessReason>,
    val recruitment: ProgramRecruitmentPhase,
)

data class ExpoFeatureReadinessResponse(
    val status: ExpoFeatureReadiness,
    val reasons: List<ExpoFeatureReadinessReason> = emptyList(),
)

data class ExpoFeatureReadinessReason(
    val code: String,
    val message: String,
)

data class ExpoFeatureAppliedResponse(
    @field:Schema(description = "자식 기능을 켜며 함께 켠 선행 기능")
    val autoEnabled: List<String>,
    @field:Schema(description = "선행 기능이 다시 켜져 실효 ON이 된 기능")
    val reactivated: List<String>,
    @field:Schema(description = "선행 기능이 꺼져 선택값은 남기고 실효 OFF가 된 기능")
    val deactivated: List<String>,
    @field:Schema(description = "기능 또는 전화 인증 값이 실제로 바뀌었는지")
    val changed: Boolean,
)
