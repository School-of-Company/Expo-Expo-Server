package team.startup.expo.domain.training.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.training.presentation.dto.request.TrainingProgramBatchRequest
import team.startup.expo.domain.training.presentation.dto.response.TrainingProgramResponse
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.domain.training.service.GetTrainingProgramBatchService
import team.startup.expo.global.common.time.toProgramDateTime
import team.startup.expo.global.exception.ExpectedException
import java.time.LocalDateTime

@Service
class GetTrainingProgramBatchServiceImpl(
    private val expoRepository: ExpoRepository,
    private val trainingProgramRepository: TrainingProgramRepository,
) : GetTrainingProgramBatchService {
    @Transactional(readOnly = true)
    override fun execute(
        expoId: String,
        request: TrainingProgramBatchRequest,
    ): List<TrainingProgramResponse> {
        expoRepository.findByIdOrNull(expoId)
            ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾을 수 없습니다.")

        // 중복 ID는 한 번만 반환하고, 없는 ID나 다른 박람회 ID가 하나라도 있으면 일부만 돌려주지 않는다
        val programIds = request.programIds.distinct()
        val programs = trainingProgramRepository.findAllByIdIn(programIds).filter { it.expo?.id == expoId }
        if (programs.size != programIds.size) {
            throw ExpectedException(HttpStatus.NOT_FOUND, "연수 프로그램을 찾지 못했습니다.")
        }

        return programs.sortedBy { it.id }.map { program ->
            TrainingProgramResponse(
                id = requireNotNull(program.id),
                title = program.title,
                startedAt = program.startedAt.toProgramDateTimeText(),
                endedAt = program.endedAt.toProgramDateTimeText(),
                category = program.category,
            )
        }
    }

    // 저장값은 "yyyy-MM-dd'T'HH:mm"과 "yyyy-MM-dd HH:mm"이 섞여 있어 내부 응답은 "yyyy-MM-dd HH:mm"으로 맞춘다
    private fun String.toProgramDateTimeText(): String = LocalDateTime.parse(replace(' ', 'T')).toProgramDateTime()
}
