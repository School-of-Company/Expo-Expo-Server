package team.startup.expo.domain.training.presentation.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull

// v1 ApplicationTrainingProListAndTraineeRequestDto와 같은 필드와 필수 조건을 유지한다
data class ApplyTrainingProgramsWithTraineeRequest(
    @field:NotNull
    @field:Schema(description = "연수자 코드. 가짜 예시", example = "sample-training-code")
    val trainingId: String,
    @field:NotNull
    @field:Schema(description = "가짜 예시 전화번호", example = "01000000000")
    val phoneNumber: String,
    @field:NotNull
    @field:Schema(description = "참가자 이름", example = "가상 참가자")
    val name: String,
    @field:NotNull
    @field:Schema(description = "추가 정보 JSON 문자열", example = "{}")
    val informationJson: String,
    @field:NotNull
    @field:Schema(description = "개인정보 제공 동의 여부", example = "true")
    val personalInformationStatus: Boolean,
    @field:NotEmpty
    @field:Schema(description = "프로그램 식별자 목록", example = "[1,2]")
    val trainingProIds: List<Long>,
)
