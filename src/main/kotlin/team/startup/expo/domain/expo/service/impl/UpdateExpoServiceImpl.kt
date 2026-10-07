package team.startup.expo.domain.expo.service.impl

import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.presentation.dto.request.UpdateExpoRequest
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.UpdateExpoService
import team.startup.expo.domain.image.service.AttachExpoImageService
import team.startup.expo.domain.standard.entity.StandardProgram
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.standard.service.StandardDependenciesClient
import team.startup.expo.domain.training.entity.TrainingProgram
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.domain.training.service.TrainingDependenciesClient
import team.startup.expo.global.exception.ExpectedException

@Service
class UpdateExpoServiceImpl(
    private val expoRepository: ExpoRepository,
    private val standardProgramRepository: StandardProgramRepository,
    private val trainingProgramRepository: TrainingProgramRepository,
    private val attachExpoImageService: AttachExpoImageService,
    private val standardDependencies: StandardDependenciesClient,
    private val trainingDependencies: TrainingDependenciesClient,
) : UpdateExpoService {
    @Transactional
    override fun execute(
        expoId: String,
        request: UpdateExpoRequest,
    ) {
        val expo =
            expoRepository.findLockedById(expoId)
                ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
        if (expo.deletingAt != null) throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
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

        val removedStandardPrograms = existingStandardPrograms.filter { it.id !in requestedStandardIds }
        val removedTrainingPrograms = existingTrainingPrograms.filter { it.id !in requestedTrainingIds }

        val uploadedBy =
            SecurityContextHolder.getContext().authentication?.name
                ?: throw ExpectedException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.")
        attachExpoImageService.execute(request.coverImage, uploadedBy, expoId, previousUrl = expo.coverImage)

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
            throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
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
        removedStandardPrograms.forEach { standardDependencies.deleteApplications(requireNotNull(it.id)) }
        removedTrainingPrograms.forEach { trainingDependencies.deleteApplications(requireNotNull(it.id)) }
        standardProgramRepository.deleteAllInBatch(removedStandardPrograms)
        trainingProgramRepository.deleteAllInBatch(removedTrainingPrograms)
    }

    private fun validateProgramIds(
        requestedIds: List<Long>,
        existingIds: Set<Long>,
    ) {
        if (requestedIds.size != requestedIds.toSet().size || requestedIds.any { it !in existingIds }) {
            throw ExpectedException(HttpStatus.CONFLICT, "박람회 프로그램 정보가 충돌합니다.")
        }
    }
}
