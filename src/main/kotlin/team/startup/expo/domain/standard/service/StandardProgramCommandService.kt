package team.startup.expo.domain.standard.service

import jakarta.validation.Validator
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.standard.entity.StandardProgram
import team.startup.expo.domain.standard.presentation.dto.request.AddStandardProgramRequest
import team.startup.expo.domain.standard.presentation.dto.request.UpdateStandardProgramRequest
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.global.exception.ExpectedException

@Service
class StandardProgramCommandService(
    private val expos: ExpoRepository,
    private val programs: StandardProgramRepository,
    private val validator: Validator,
) {
    @Transactional
    fun create(
        expoId: String,
        request: AddStandardProgramRequest,
    ) {
        createAll(expoId, listOf(request))
    }

    @Transactional
    fun createAll(
        expoId: String,
        requests: List<AddStandardProgramRequest>,
    ) {
        if (requests.any { validator.validate(it).isNotEmpty() }) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "잘못된 요청입니다.")
        }
        val expo =
            expos.findByIdOrNull(expoId)
                ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
        programs.saveAll(
            requests.map { request ->
                StandardProgram(
                    title = request.title,
                    startedAt = request.startedAt.toString(),
                    endedAt = request.endedAt.toString(),
                    expo = expo,
                )
            },
        )
    }

    @Transactional
    fun update(
        programId: Long,
        request: UpdateStandardProgramRequest,
    ) {
        val program =
            programs.findByIdOrNull(programId)
                ?: throw ExpectedException(HttpStatus.NOT_FOUND, "일반 프로그램을 찾지 못 했습니다.")
        programs.save(
            StandardProgram(
                id = program.id,
                title = request.title,
                startedAt = request.startedAt.toString(),
                endedAt = request.endedAt.toString(),
                expo = program.expo,
            ),
        )
    }
}
