package team.startup.expo.domain.standard.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.standard.entity.StandardProgram
import team.startup.expo.domain.standard.presentation.dto.request.UpdateStandardProgramRequest
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.standard.service.UpdateStandardProgramService
import team.startup.expo.global.exception.ExpectedException

@Service
class UpdateStandardProgramServiceImpl(
    private val programs: StandardProgramRepository,
    private val expos: ExpoRepository,
) : UpdateStandardProgramService {
    @Transactional
    override fun execute(
        programId: Long,
        request: UpdateStandardProgramRequest,
    ) {
        val program =
            programs.findByIdOrNull(programId)
                ?: throw ExpectedException(HttpStatus.NOT_FOUND, "일반 프로그램을 찾지 못 했습니다.")
        val expoId = program.expo?.id ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
        expos.findLockedById(expoId) ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
        if (expos.existsByIdAndDeletingAtIsNotNull(expoId)) throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
        if (!programs.existsById(programId)) throw ExpectedException(HttpStatus.NOT_FOUND, "일반 프로그램을 찾지 못 했습니다.")
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
