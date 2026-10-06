package team.startup.expo.domain.training.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.training.entity.TrainingProgram
import team.startup.expo.domain.training.presentation.dto.request.AddTrainingProgramRequest
import team.startup.expo.domain.training.presentation.dto.request.UpdateTrainingProgramRequest
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.domain.training.service.TrainingProgramWriteService
import team.startup.expo.global.exception.ExpectedException

@Service
class TrainingProgramWriteServiceImpl(
    private val expoRepository: ExpoRepository,
    private val trainingProgramRepository: TrainingProgramRepository,
) : TrainingProgramWriteService {
    @Transactional
    override fun add(
        expoId: String,
        request: AddTrainingProgramRequest,
    ) {
        val expo = findExpo(expoId)
        trainingProgramRepository.save(request.toEntity(expo))
    }

    @Transactional
    override fun addAll(
        expoId: String,
        requests: List<AddTrainingProgramRequest>,
    ) {
        val expo = findExpo(expoId)
        trainingProgramRepository.saveAll(requests.map { it.toEntity(expo) })
    }

    @Transactional
    override fun update(
        trainingProgramId: Long,
        request: UpdateTrainingProgramRequest,
    ) {
        val existing =
            trainingProgramRepository.findByIdOrNull(trainingProgramId)
                ?: throw ExpectedException(HttpStatus.NOT_FOUND, "연수 프로그램을 찾지 못했습니다.")
        trainingProgramRepository.save(
            TrainingProgram(
                id = existing.id,
                title = request.title,
                startedAt = request.startedAt.toString(),
                endedAt = request.endedAt.toString(),
                category = request.category,
                expo = existing.expo,
            ),
        )
    }

    private fun findExpo(expoId: String): Expo {
        val expo =
            expoRepository.findLockedById(expoId)
                ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾을 수 없습니다.")
        if (expo.deletingAt != null) throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
        return expo
    }

    private fun AddTrainingProgramRequest.toEntity(expo: Expo): TrainingProgram =
        TrainingProgram(
            title = title,
            startedAt = startedAt.toString(),
            endedAt = endedAt.toString(),
            category = category,
            expo = expo,
        )
}
