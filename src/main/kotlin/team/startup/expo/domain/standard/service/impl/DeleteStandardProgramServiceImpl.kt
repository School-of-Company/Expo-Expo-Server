package team.startup.expo.domain.standard.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.standard.service.DeleteStandardProgramService
import team.startup.expo.domain.standard.service.StandardDependenciesClient
import team.startup.expo.global.exception.ExpectedException

@Service
class DeleteStandardProgramServiceImpl(
    private val programs: StandardProgramRepository,
    private val dependencies: StandardDependenciesClient,
    private val expos: ExpoRepository,
) : DeleteStandardProgramService {
    @Transactional
    override fun execute(programId: Long) {
        val program =
            programs.findByIdOrNull(programId)
                ?: throw ExpectedException(HttpStatus.NOT_FOUND, "일반 프로그램을 찾지 못 했습니다.")
        val expoId = program.expo?.id ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
        val expo =
            expos.findLockedById(expoId)
                ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
        if (expo.deletingAt != null) throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
        dependencies.deleteApplications(programId)
        programs.delete(program)
    }
}
