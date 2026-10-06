package team.startup.expo.domain.standard.service.impl

import jakarta.validation.Validator
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.standard.entity.StandardProgram
import team.startup.expo.domain.standard.presentation.dto.request.AddStandardProgramRequest
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.standard.service.CreateStandardProgramListService
import team.startup.expo.global.exception.ExpectedException

@Service
class CreateStandardProgramListServiceImpl(
    private val expos: ExpoRepository,
    private val programs: StandardProgramRepository,
    private val validator: Validator,
) : CreateStandardProgramListService {
    @Transactional
    override fun execute(
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
}
