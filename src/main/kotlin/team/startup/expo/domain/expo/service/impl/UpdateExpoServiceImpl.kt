package team.startup.expo.domain.expo.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.presentation.dto.request.UpdateExpoRequest
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.UpdateExpoService
import team.startup.expo.domain.standard.entity.StandardProgram
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.training.entity.TrainingProgram
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.global.exception.ExpectedException

@Service
class UpdateExpoServiceImpl(
    private val expoRepository: ExpoRepository,
    private val standardProgramRepository: StandardProgramRepository,
    private val trainingProgramRepository: TrainingProgramRepository,
) : UpdateExpoService {
    @Transactional
    override fun execute(
        expoId: String,
        request: UpdateExpoRequest,
    ) {
        val expo =
            expoRepository.findByIdOrNull(expoId)
                ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾을 수 없습니다.")
        val existingStandardPrograms = standardProgramRepository.findByExpo(expo)
        val existingTrainingPrograms = trainingProgramRepository.findByExpo(expo)
        val requestedStandardIds = request.updateStandardProRequestDto.mapNotNull { it.id }
        val requestedTrainingIds = request.updateTrainingProRequestDto.mapNotNull { it.id }

        validateProgramIds(
            requestedIds = requestedStandardIds,
            existingIds = existingStandardPrograms.mapNotNull { it.id }.toSet(),
        )
        validateProgramIds(
            requestedIds = requestedTrainingIds,
            existingIds = existingTrainingPrograms.mapNotNull { it.id }.toSet(),
        )

        val updatedRows =
            expoRepository.updateInfo(
                id = expoId,
                title = request.title,
                description = request.description,
                startedDay = request.startedDay.toString(),
                finishedDay = request.finishedDay.toString(),
                location = request.location,
                coverImage = request.coverImage,
                x = request.x,
                y = request.y,
            )
        if (updatedRows != 1) {
            throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾을 수 없습니다.")
        }

        val updatedExpo = expoRepository.getReferenceById(expoId)
        standardProgramRepository.saveAllAndFlush(
            request.updateStandardProRequestDto.map { program ->
                StandardProgram(
                    id = program.id,
                    title = program.title,
                    startedAt = program.startedAt.toString(),
                    endedAt = program.endedAt.toString(),
                    expo = updatedExpo,
                )
            },
        )
        trainingProgramRepository.saveAllAndFlush(
            request.updateTrainingProRequestDto.map { program ->
                TrainingProgram(
                    id = program.id,
                    title = program.title,
                    startedAt = program.startedAt.toString(),
                    endedAt = program.endedAt.toString(),
                    category = program.category,
                    expo = updatedExpo,
                )
            },
        )
    }

    private fun validateProgramIds(
        requestedIds: List<Long>,
        existingIds: Set<Long>,
    ) {
        if (requestedIds.size != requestedIds.toSet().size ||
            requestedIds.any { it !in existingIds } ||
            existingIds.any { it !in requestedIds }
        ) {
            throw ExpectedException(HttpStatus.CONFLICT, "박람회 프로그램 정보가 충돌합니다.")
        }
    }
}
