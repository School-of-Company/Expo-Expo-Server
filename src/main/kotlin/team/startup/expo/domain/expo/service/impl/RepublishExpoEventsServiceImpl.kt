package team.startup.expo.domain.expo.service.impl

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import team.startup.expo.domain.expo.presentation.dto.response.RepublishExpoEventsResponse
import team.startup.expo.domain.expo.service.ExpoEventSupport
import team.startup.expo.domain.expo.service.RepublishExpoEventsService
import team.startup.expo.global.exception.ExpectedException

// 받는 쪽이 프로젝션을 처음 만들거나 다시 만들 때 쓴다. 모든 박람회의 현재 상태를 같은 토픽에 다시 싣는다.
@Service
class RepublishExpoEventsServiceImpl(
    private val events: ExpoEventSupport,
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
) : RepublishExpoEventsService {
    override fun execute(): RepublishExpoEventsResponse {
        if (!events.isEnabled) throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "박람회 변경 이벤트 발행이 꺼져 있습니다.")
        var after = ""
        var enqueued = 0
        while (true) {
            val ids =
                jdbc.query(
                    "SELECT id FROM tb_expo WHERE id > ? ORDER BY id LIMIT ?",
                    { row, _ -> row.getString(1) },
                    after,
                    PAGE_SIZE,
                )
            if (ids.isEmpty()) break
            // 페이지마다 따로 커밋해 락을 오래 잡지 않는다.
            transactions.executeWithoutResult { ids.forEach(events::enqueue) }
            enqueued += ids.size
            after = ids.last()
            if (ids.size < PAGE_SIZE) break
        }
        return RepublishExpoEventsResponse(enqueued)
    }

    private companion object {
        const val PAGE_SIZE = 100
    }
}
