package team.startup.expo.domain.expo.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.presentation.dto.request.ProgramFormChangeKind
import team.startup.expo.domain.expo.presentation.dto.request.ProgramFormChangeRequest
import team.startup.expo.domain.expo.presentation.dto.response.ProgramFormChangeResponse
import team.startup.expo.domain.expo.repository.ProgramFormChangeRepository
import team.startup.expo.domain.expo.service.ReserveProgramFormChangeService
import team.startup.expo.global.exception.ExpectedException
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID

@Service
class ReserveProgramFormChangeServiceImpl(
    private val forms: ProgramFormChangeRepository,
    private val mapper: ObjectMapper,
) : ReserveProgramFormChangeService {
    @Transactional
    override fun execute(
        formId: UUID,
        changeId: UUID,
        request: ProgramFormChangeRequest,
    ): ProgramFormChangeResponse {
        request.validate()
        forms.lock()
        forms.find(formId, changeId)?.let { receipt ->
            if (receipt.request != mapper.writeValueAsString(request)) conflict("같은 변경 키에 다른 요청을 사용할 수 없습니다.")
            if (receipt.released || !forms.now().isBefore(requireNotNull(receipt.expiresAt))) conflict("해제되거나 만료된 폼 변경 예약입니다.")
            return mapper.readValue(requireNotNull(receipt.response), ProgramFormChangeResponse::class.java)
        }
        if (forms.blocked(formId)) conflict("진행 중이거나 복구가 필요한 폼 변경이 있습니다.")
        val usage = forms.usage(formId)
        when (request.kind) {
            ProgramFormChangeKind.UPDATE -> {
                if (forms.guardianReferenced(formId)) conflict("보호자 동반 정책에서 사용 중인 폼은 수정할 수 없습니다")
            }

            ProgramFormChangeKind.DELETE -> {
                if (usage.referenced) conflict("프로그램이 참조하는 폼은 삭제할 수 없습니다.")
            }

            ProgramFormChangeKind.SHRINK -> {
                if (usage.referenced &&
                    (
                        requireNotNull(request.startDate).isAfter(usage.applicationStartedAt) ||
                            requireNotNull(request.endDate).isBefore(usage.applicationEndedAt)
                    )
                ) {
                    conflict("폼 기간이 모든 프로그램 모집 기간을 포함해야 합니다.")
                }
            }
        }
        val response =
            ProgramFormChangeResponse(
                formId,
                usage.referenced,
                usage.applicationStartedAt,
                usage.applicationEndedAt,
                changeId,
                forms.now().plus(Duration.ofMinutes(5)),
            )
        forms.reserve(formId, changeId, mapper.writeValueAsString(request), mapper.writeValueAsString(response), response.expiresAt)
        return response
    }

    private fun conflict(message: String): Nothing = throw ExpectedException(HttpStatus.CONFLICT, message)
}
