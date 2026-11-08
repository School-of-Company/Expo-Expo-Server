package team.startup.expo.domain.expo.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.expo.entity.ExpoMember
import team.startup.expo.domain.expo.entity.ExpoMemberRole
import team.startup.expo.domain.expo.presentation.dto.request.AdminChangeTargetType
import team.startup.expo.domain.expo.presentation.dto.request.AdminChangeType
import team.startup.expo.domain.expo.presentation.dto.request.CreateExpoRequest
import team.startup.expo.domain.expo.presentation.dto.response.CreateExpoResponse
import team.startup.expo.domain.expo.presentation.dto.response.ProgramType
import team.startup.expo.domain.expo.repository.AdminChangeHistoryRepository
import team.startup.expo.domain.expo.repository.ExpoMemberRepository
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.AdminAccessSupport
import team.startup.expo.domain.expo.service.CreateExpoService
import team.startup.expo.domain.expo.service.ExpoEventSupport
import team.startup.expo.domain.expo.service.ProgramPolicySupport
import team.startup.expo.domain.image.service.AttachExpoImageService
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.standard.service.StandardProgramCreationSupport
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.domain.training.service.TrainingProgramCreationSupport
import team.startup.expo.global.common.id.ExpoIdGenerator
import team.startup.expo.global.exception.ExpectedException

@Service
class CreateExpoServiceImpl(
    private val admins: AdminAccessSupport,
    private val expoRepository: ExpoRepository,
    private val policies: ProgramPolicySupport,
    private val standardProgramRepository: StandardProgramRepository,
    private val standardCreation: StandardProgramCreationSupport,
    private val trainingProgramRepository: TrainingProgramRepository,
    private val trainingCreation: TrainingProgramCreationSupport,
    private val expoIdGenerator: ExpoIdGenerator,
    private val attachExpoImageService: AttachExpoImageService,
    private val history: AdminChangeHistoryRepository,
    private val members: ExpoMemberRepository,
    private val events: ExpoEventSupport,
) : CreateExpoService {
    override fun execute(request: CreateExpoRequest): CreateExpoResponse {
        // 폼 검증을 트랜잭션 밖에서 먼저 하도록 ID를 미리 만든다.
        val expoId = expoIdGenerator.generate()
        val programs =
            request.addStandardProRequestDto.map { ProgramType.STANDARD to it.policy } +
                request.addTrainingProRequestDto.map { ProgramType.TRAINING to it.policy }
        return requireNotNull(
            policies.creating(expoId, programs) {
                request.validatePeriod()
                request.validatePreregisterCapacities()
                val expo =
                    Expo(
                        id = expoId,
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
                        preregisterTeacherCapacity = request.preregisterTeacherCapacity,
                        preregisterNonTeacherCapacity = request.preregisterNonTeacherCapacity,
                    )
                expoRepository.saveAndFlush(expo)
                events.changed(expo.id)
                val creatorId =
                    admins.currentAdminId() ?: throw ExpectedException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.")
                members.saveAndFlush(ExpoMember(expo.id, creatorId, ExpoMemberRole.OWNER))

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
                                    place = program.place,
                                )
                            standardCreation.toEntity(expo, definition)
                        },
                    ).forEach {
                        history.record(
                            expo.id,
                            AdminChangeType.PROGRAM_CREATE,
                            AdminChangeTargetType.STANDARD,
                            requireNotNull(it.id),
                            it.title,
                            creatorId,
                        )
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
                                    place = program.place,
                                )
                            trainingCreation.toEntity(expo, definition)
                        },
                    ).forEach {
                        history.record(
                            expo.id,
                            AdminChangeType.PROGRAM_CREATE,
                            AdminChangeTargetType.TRAINING,
                            requireNotNull(it.id),
                            it.title,
                            creatorId,
                        )
                    }

                val uploadedBy =
                    admins.currentName() ?: throw ExpectedException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.")
                attachExpoImageService.execute(request.coverImage, uploadedBy, expo.id)

                CreateExpoResponse(expoId = expo.id)
            },
        )
    }
}
