package team.startup.expo.domain.standard.service

import jakarta.validation.Validator
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.expo.presentation.dto.request.AdminChangeTargetType
import team.startup.expo.domain.expo.presentation.dto.request.AdminChangeType
import team.startup.expo.domain.expo.presentation.dto.request.ProgramSchedule
import team.startup.expo.domain.expo.presentation.dto.request.validateProgramPeriod
import team.startup.expo.domain.expo.presentation.dto.response.ProgramDefinition
import team.startup.expo.domain.expo.presentation.dto.response.ProgramType
import team.startup.expo.domain.expo.repository.AdminChangeHistoryRepository
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.AdminAccessSupport
import team.startup.expo.domain.expo.service.ExpoEventSupport
import team.startup.expo.domain.expo.service.ProgramPolicySupport
import team.startup.expo.domain.standard.entity.StandardProgram
import team.startup.expo.domain.standard.presentation.dto.request.AddStandardProgramRequest
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.global.exception.ExpectedException

@Component
class StandardProgramCreationSupport(
    private val admins: AdminAccessSupport,
    private val expos: ExpoRepository,
    private val policies: ProgramPolicySupport,
    private val programs: StandardProgramRepository,
    private val validator: Validator,
    private val history: AdminChangeHistoryRepository,
    private val events: ExpoEventSupport,
) {
    fun create(
        expoId: String,
        requests: List<AddStandardProgramRequest>,
    ) {
        policies.creating(expoId, requests.map { ProgramType.STANDARD to it.policy }) {
            if (requests.any { validator.validate(it).isNotEmpty() }) {
                throw ExpectedException(HttpStatus.BAD_REQUEST, "잘못된 요청입니다.")
            }
            val expo =
                expos.findLockedById(expoId)
                    ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
            if (expo.deletingAt != null) throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
            expo.validateProgramPeriod(requests.map { ProgramSchedule(it.title, it.startedAt, it.endedAt) })
            events.changed(expoId)
            programs
                .saveAll(
                    requests.map { request ->
                        val definition =
                            policies.create(
                                expoId,
                                ProgramType.STANDARD,
                                request.title,
                                request.startedAt.toString(),
                                request.endedAt.toString(),
                                null,
                                request.policy,
                                place = request.place,
                            )
                        toEntity(expo, definition)
                    },
                ).forEach {
                    history.record(
                        expoId,
                        AdminChangeType.PROGRAM_CREATE,
                        AdminChangeTargetType.STANDARD,
                        requireNotNull(it.id),
                        it.title,
                        admins.currentAdminId(),
                    )
                }
        }
    }

    fun toEntity(
        expo: Expo,
        definition: ProgramDefinition,
    ): StandardProgram =
        StandardProgram(
            title = definition.title,
            startedAt = definition.startedAt,
            endedAt = definition.endedAt,
            expo = expo,
            groupId = definition.groupId,
            policyJson = policies.json(definition.policy),
            place = definition.place,
        )
}
