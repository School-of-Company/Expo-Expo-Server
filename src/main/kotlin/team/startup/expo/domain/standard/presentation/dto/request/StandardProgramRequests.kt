package team.startup.expo.domain.standard.presentation.dto.request

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.validation.constraints.NotNull
import org.hibernate.validator.constraints.CodePointLength
import java.time.LocalDateTime

data class AddStandardProgramRequest(
    @field:NotNull
    @field:CodePointLength(max = 50)
    val title: String,
    @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    val startedAt: LocalDateTime,
    @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    val endedAt: LocalDateTime,
)

data class UpdateStandardProgramRequest(
    val id: Long,
    @field:NotNull
    @field:CodePointLength(max = 50)
    val title: String,
    @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    val startedAt: LocalDateTime,
    @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    val endedAt: LocalDateTime,
)

data class ApplyStandardProgramsRequest(
    @field:NotNull
    val phoneNumber: String,
    @field:NotNull
    val standardProIds: List<Long>,
)
