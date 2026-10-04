package team.startup.expo.domain.expo.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.expo.presentation.dto.request.CreateExpoRequest
import team.startup.expo.domain.expo.presentation.dto.response.CreateExpoResponse
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.CreateExpoService
import team.startup.expo.domain.standard.entity.StandardProgram
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.training.entity.TrainingProgram
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.global.common.id.ExpoIdGenerator

@Service
class CreateExpoServiceImpl(
    private val expoRepository: ExpoRepository,
    private val standardProgramRepository: StandardProgramRepository,
    private val trainingProgramRepository: TrainingProgramRepository,
    private val expoIdGenerator: ExpoIdGenerator,
) : CreateExpoService {
    @Transactional
    override fun execute(request: CreateExpoRequest): CreateExpoResponse {
        val expo =
            Expo(
                id = expoIdGenerator.generate(),
                title = request.title,
                description = request.description,
                startedDay = request.startedDay.toString(),
                finishedDay = request.finishedDay.toString(),
                location = request.location,
                coverImage = request.coverImage,
                x = request.x,
                y = request.y,
                applicationPerson = 0L,
                yesterdayApplicationPerson = 0L,
            )
        expoRepository.saveAndFlush(expo)

        standardProgramRepository.saveAllAndFlush(
            request.addStandardProRequestDto.map { program ->
                StandardProgram(
                    title = program.title,
                    startedAt = program.startedAt.toString(),
                    endedAt = program.endedAt.toString(),
                    expo = expo,
                )
            },
        )
        trainingProgramRepository.saveAllAndFlush(
            request.addTrainingProRequestDto.map { program ->
                TrainingProgram(
                    title = program.title,
                    startedAt = program.startedAt.toString(),
                    endedAt = program.endedAt.toString(),
                    category = program.category,
                    expo = expo,
                )
            },
        )

        return CreateExpoResponse(expoId = expo.id)
    }
}
