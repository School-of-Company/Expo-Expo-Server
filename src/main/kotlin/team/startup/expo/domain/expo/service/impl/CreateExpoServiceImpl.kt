package team.startup.expo.domain.expo.service.impl

import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.expo.presentation.dto.request.CreateExpoRequest
import team.startup.expo.domain.expo.presentation.dto.response.CreateExpoResponse
import team.startup.expo.domain.expo.presentation.dto.response.ProgramType
import team.startup.expo.domain.expo.repository.AdminChangeHistoryRepository
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.CreateExpoService
import team.startup.expo.domain.expo.service.ProgramPolicySupport
import team.startup.expo.domain.image.service.AttachExpoImageService
import team.startup.expo.domain.standard.entity.StandardProgram
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.training.entity.TrainingProgram
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.global.common.id.ExpoIdGenerator
import team.startup.expo.global.exception.ExpectedException

@Service
class CreateExpoServiceImpl(
    private val expoRepository: ExpoRepository,
    private val policies: ProgramPolicySupport,
    private val standardProgramRepository: StandardProgramRepository,
    private val trainingProgramRepository: TrainingProgramRepository,
    private val expoIdGenerator: ExpoIdGenerator,
    private val attachExpoImageService: AttachExpoImageService,
    private val history: AdminChangeHistoryRepository,
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

        standardProgramRepository
            .saveAllAndFlush(
                request.addStandardProRequestDto.map { program ->
                    val definition =
                        policies.create(
                            expo.id,
                            ProgramType.STANDARD,
                            program.title,
                            program.startedAt.toString(),
                            program.endedAt.toString(),
                            null,
                            program.policy,
                        )
                    StandardProgram(
                        title = program.title,
                        startedAt = program.startedAt.toString(),
                        endedAt = program.endedAt.toString(),
                        expo = expo,
                        groupId = definition.groupId,
                        policyJson = policies.json(definition.policy),
                    )
                },
            ).forEach {
                history.record(expo.id, "PROGRAM_CREATE", "STANDARD", requireNotNull(it.id), it.title, history.currentAdminId())
            }
        trainingProgramRepository
            .saveAllAndFlush(
                request.addTrainingProRequestDto.map { program ->
                    val definition =
                        policies.create(
                            expo.id,
                            ProgramType.TRAINING,
                            program.title,
                            program.startedAt.toString(),
                            program.endedAt.toString(),
                            program.category,
                            program.policy,
                        )
                    TrainingProgram(
                        title = program.title,
                        startedAt = program.startedAt.toString(),
                        endedAt = program.endedAt.toString(),
                        category = program.category,
                        expo = expo,
                        groupId = definition.groupId,
                        policyJson = policies.json(definition.policy),
                    )
                },
            ).forEach {
                history.record(expo.id, "PROGRAM_CREATE", "TRAINING", requireNotNull(it.id), it.title, history.currentAdminId())
            }

        val uploadedBy =
            SecurityContextHolder.getContext().authentication?.name
                ?: throw ExpectedException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.")
        attachExpoImageService.execute(request.coverImage, uploadedBy, expo.id)

        return CreateExpoResponse(expoId = expo.id)
    }
}
