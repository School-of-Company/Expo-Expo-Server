package team.startup.expo.domain.expo.service.impl

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.presentation.dto.request.InternalExpoPageRequest
import team.startup.expo.domain.expo.presentation.dto.response.InternalExpoPageResponse
import team.startup.expo.domain.expo.presentation.dto.response.InternalExpoSummaryResponse
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.GetInternalExpoListService
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

@Service
class GetInternalExpoListServiceImpl(
    private val expoRepository: ExpoRepository,
    // 박람회 날짜는 한국 시간 기준이다
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) : GetInternalExpoListService {
    @Transactional(readOnly = true)
    override fun execute(request: InternalExpoPageRequest): InternalExpoPageResponse {
        // finished_day는 yyyy-MM-dd 문자열이라 사전순 비교가 날짜 비교와 같다
        val result =
            expoRepository.findAllByDeletingAtIsNullAndFinishedDayGreaterThanEqual(
                LocalDate.now(clock).toString(),
                PageRequest.of(request.page, request.size, Sort.by("startedDay", "id")),
            )
        return InternalExpoPageResponse(
            content = result.content.map { InternalExpoSummaryResponse(it.id, it.title, it.startedDay, it.finishedDay) },
            hasNext = result.hasNext(),
        )
    }
}
