package team.startup.expo.domain.expo.service.impl

import org.springframework.dao.DataAccessException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.presentation.dto.request.ProgramSessionPageRequest
import team.startup.expo.domain.expo.presentation.dto.response.ProgramSessionPageResponse
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.repository.ProgramChangeRepository
import team.startup.expo.domain.expo.repository.ProgramSessionQueryRepository
import team.startup.expo.domain.expo.service.GetProgramSessionPageService
import team.startup.expo.global.exception.ExpectedException

@Service
class GetProgramSessionPageServiceImpl(
    private val expos: ExpoRepository,
    private val changes: ProgramChangeRepository,
    private val programs: ProgramSessionQueryRepository,
) : GetProgramSessionPageService {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun execute(
        expoId: String,
        request: ProgramSessionPageRequest,
    ): ProgramSessionPageResponse {
        if ((request.programId != null && request.programType == null) || (request.page > 0 && request.definitionVersion == null)) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "회차 ID에는 유형이, 다음 페이지에는 definitionVersion이 필요합니다.")
        }
        try {
            val expo = expos.findByIdOrNull(expoId) ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
            if (expo.deletingAt != null || changes.existsById(expoId)) {
                throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "행사 정의 변경 중입니다. 잠시 후 다시 조회해 주세요.")
            }
            val version = programs.version(expoId)
            if (request.definitionVersion != null && request.definitionVersion != version) {
                throw ExpectedException(HttpStatus.CONFLICT, "행사 정의 버전이 변경됐습니다. 처음부터 다시 조회해 주세요.")
            }
            val total = programs.count(expoId, request)
            if (total == 0L && (request.programId != null || request.groupId != null)) {
                throw ExpectedException(HttpStatus.NOT_FOUND, "행사의 프로그램 회차 또는 묶음을 찾지 못 했습니다.")
            }
            return ProgramSessionPageResponse(expoId, programs.page(expoId, request), request.page, request.size, total, version)
        } catch (exception: DataAccessException) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "행사 정의를 조회할 수 없습니다.")
        }
    }
}
