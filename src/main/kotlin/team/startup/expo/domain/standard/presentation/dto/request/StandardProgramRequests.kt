package team.startup.expo.domain.standard.presentation.dto.request

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.hibernate.validator.constraints.CodePointLength
import team.startup.expo.domain.expo.presentation.dto.request.ProgramPolicyRequest
import java.time.LocalDateTime

data class AddStandardProgramRequest(
    @field:NotNull
    @field:CodePointLength(max = 50)
    val title: String,
    @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    val startedAt: LocalDateTime,
    @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    val endedAt: LocalDateTime,
    @field:Valid
    val policy: ProgramPolicyRequest? = null,
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
    @field:Valid
    val policy: ProgramPolicyRequest? = null,
    @field:jakarta.validation.constraints.Positive
    val revision: Long? = null,
)

data class ApplyStandardProgramsRequest(
    @field:NotNull
    val phoneNumber: String,
    @field:NotNull
    val standardProIds: List<Long>,
)
