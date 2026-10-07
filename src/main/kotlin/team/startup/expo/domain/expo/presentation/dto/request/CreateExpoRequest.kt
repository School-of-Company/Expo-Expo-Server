package team.startup.expo.domain.expo.presentation.dto.request

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.hibernate.validator.constraints.CodePointLength
import team.startup.expo.domain.training.entity.Category
import java.time.LocalDate
import java.time.LocalDateTime

data class CreateExpoRequest(
    val title: String,
    @field:Size(max = 1000)
    val description: String,
    @field:JsonFormat(pattern = "yyyy-MM-dd")
    val startedDay: LocalDate,
    @field:JsonFormat(pattern = "yyyy-MM-dd")
    val finishedDay: LocalDate,
    val location: String,
    val coverImage: String,
    @field:CodePointLength(max = 15)
    val x: String,
    @field:CodePointLength(max = 15)
    val y: String,
    @field:Valid
    val addStandardProRequestDto: List<CreateStandardProgramRequest>,
    @field:Valid
    val addTrainingProRequestDto: List<CreateTrainingProgramRequest>,
)

data class CreateStandardProgramRequest(
    @field:CodePointLength(max = 50)
    val title: String,
    @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    val startedAt: LocalDateTime,
    @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    val endedAt: LocalDateTime,
)

data class CreateTrainingProgramRequest(
    @field:CodePointLength(max = 50)
    val title: String,
    @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    val startedAt: LocalDateTime,
    @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    val endedAt: LocalDateTime,
    val category: Category,
)
