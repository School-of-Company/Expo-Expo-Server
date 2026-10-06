package team.startup.expo.domain.expo.service.impl

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.RecordStandardRegistrationService
import team.startup.expo.global.exception.ExpectedException
import java.util.UUID

@Service
class RecordStandardRegistrationServiceImpl(
    private val expos: ExpoRepository,
    private val jdbc: JdbcTemplate,
) : RecordStandardRegistrationService {
    @Transactional
    override fun execute(
        expoId: String,
        participantId: Long,
    ) {
        if (participantId <= 0 || runCatching { UUID.fromString(expoId).toString() }.getOrNull() != expoId) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "잘못된 요청입니다.")
        }
        val expo =
            expos.findLockedById(expoId)
                ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
        if (expo.deletingAt != null) throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
        // 원장에 새 행이 기록된 경우에만 증가한다. 같은 박람회 요청은 위 행 잠금으로 직렬화된다.
        val inserted =
            jdbc.update(
                "INSERT INTO tb_standard_registration (expo_id, participant_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                expoId,
                participantId,
            )
        if (inserted == 1) expo.plusApplicationPerson()
    }
}
