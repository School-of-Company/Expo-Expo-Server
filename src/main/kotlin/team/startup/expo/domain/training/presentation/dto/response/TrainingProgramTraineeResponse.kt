package team.startup.expo.domain.training.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema

data class TrainingProgramTraineeResponse(
    @field:Schema(description = "식별자", example = "1")
    val id: Long,
    @field:Schema(description = "참가자 이름", example = "가상 참가자")
    val name: String,
    @field:Schema(description = "프로그램 제목", example = "예시 프로그램")
    val programName: String,
    @field:Schema(description = "출석 여부", example = "true")
    val status: Boolean,
    @field:Schema(description = "입장 시각 HH:mm:ss", example = "10:00:00")
    val entryTime: String?,
    @field:Schema(description = "퇴장 시각 HH:mm:ss", example = "11:00:00")
    val leaveTime: String?,
)
