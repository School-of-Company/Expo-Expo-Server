package team.startup.expo.domain.standard.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.standard.presentation.dto.response.StandardProgramTitleResponse
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.standard.service.GetStandardProgramService
import team.startup.expo.global.exception.ExpectedException

@Service
class GetStandardProgramServiceImpl(
    private val expoRepository: ExpoRepository,
    private val standardProgramRepository: StandardProgramRepository,
) : GetStandardProgramService {
    @Transactional(readOnly = true)
    override fun execute(
        expoId: String,
        programId: Long,
    ): StandardProgramTitleResponse {
        expoRepository.findByIdOrNull(expoId)
            ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾을 수 없습니다.")
        val program =
            standardProgramRepository.findByIdAndExpoId(programId, expoId)
                ?: throw ExpectedException(HttpStatus.NOT_FOUND, "일반 프로그램을 찾지 못 했습니다.")

        return StandardProgramTitleResponse(id = requireNotNull(program.id), title = program.title)
    }
}
