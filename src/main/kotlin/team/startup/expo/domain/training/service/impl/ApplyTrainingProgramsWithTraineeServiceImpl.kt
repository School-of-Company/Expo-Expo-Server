package team.startup.expo.domain.training.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.training.presentation.dto.request.ApplyTrainingProgramsWithTraineeRequest
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.domain.training.service.ApplyTrainingProgramsWithTraineeService
import team.startup.expo.domain.training.service.TrainingApplicationSmsService
import team.startup.expo.domain.training.service.TrainingDependenciesClient
import team.startup.expo.global.exception.ExpectedException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

// v1 ApplicationTrainingProListAndTraineeServiceImpl: 박람회·사전 등록 기간을 확인한 뒤 연수자를 만들거나 재사용하고
// 신청을 요청한 프로그램으로 통째로 바꾼다. 신청이 실패해도 연수자는 지우지 않으며, 재시도하면 같은 연수자를 다시 쓴다.
@Service
class ApplyTrainingProgramsWithTraineeServiceImpl(
    private val expos: ExpoRepository,
    private val programs: TrainingProgramRepository,
    private val dependencies: TrainingDependenciesClient,
    private val sms: TrainingApplicationSmsService,
    private val transactions: TransactionTemplate,
    // 박람회 날짜는 한국 시간 기준이다. 테스트는 Clock 빈으로 바꾼다.
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) : ApplyTrainingProgramsWithTraineeService {
    override fun execute(
        expoId: String,
        request: ApplyTrainingProgramsWithTraineeRequest,
    ) {
        if (request.trainingProIds.any { it <= 0 }) throw ExpectedException(HttpStatus.BAD_REQUEST, "연수 프로그램 ID가 올바르지 않습니다.")
        val ids = request.trainingProIds.distinct()
        val (expo, found) =
            requireNotNull(
                transactions.execute {
                    val expo = expos.findLockedById(expoId) ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
                    if (expo.deletingAt != null) throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
                    val today = LocalDate.now(clock)
                    if (today < LocalDate.parse(expo.startedDay) || today > LocalDate.parse(expo.finishedDay)) {
                        throw ExpectedException(HttpStatus.BAD_REQUEST, "해당 박람회는 진행 중인 상태가 아닙니다.")
                    }
                    expo to programs.findAllByIdIn(ids)
                },
            )
        val form = dependencies.traineePreFormPeriod(expoId)
        val now = clock.instant()
        if (now < form.startDate || now > form.endDate) throw ExpectedException(HttpStatus.BAD_REQUEST, "사전 등록 기간이 아닙니다.")
        if (found.size != ids.size || found.any { it.expo?.id != expoId }) {
            throw ExpectedException(HttpStatus.NOT_FOUND, "연수 프로그램을 찾지 못했습니다.")
        }
        dependencies.requireApplicationConfiguration()
        val traineeId = dependencies.resolveOrCreateTrainee(expoId, request)
        sms.execute(expo, traineeId, found, "REPLACE") { dependencies.replaceApplications(expoId, traineeId, found) }
    }
}
