package team.startup.expo.domain.training.presentation.dto.request

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.validation.constraints.NotNull
import org.hibernate.validator.constraints.CodePointLength
import team.startup.expo.domain.training.entity.Category
import java.time.LocalDateTime

data class AddTrainingProgramRequest(
    @field:NotNull
    @field:CodePointLength(max = 50)
    val title: String,
    @field:NotNull
    @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    val startedAt: LocalDateTime,
    @field:NotNull
    @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    val endedAt: LocalDateTime,
    @field:NotNull
    val category: Category,
)

data class UpdateTrainingProgramRequest(
    @field:NotNull
    val id: Long,
    @field:NotNull
    @field:CodePointLength(max = 50)
    val title: String,
    @field:NotNull
    @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    val startedAt: LocalDateTime,
    @field:NotNull
    @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    val endedAt: LocalDateTime,
    @field:NotNull
    val category: Category,
)
