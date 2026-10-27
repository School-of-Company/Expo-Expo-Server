package team.startup.expo.domain.expo.service

import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.repository.findByIdOrNull
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import team.startup.expo.domain.expo.messaging.ExpoEventSnapshot
import team.startup.expo.domain.expo.repository.ExpoEventOutboxRepository
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.repository.PreregisterSessionRepository
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import tools.jackson.databind.ObjectMapper

// 박람회·프로그램·사전등록 회차를 바꾼 트랜잭션이 커밋되기 직전에 바뀐 박람회의 전체 상태를 아웃박스에 쓴다(docs/expo-change-events.md).
// 꺼져 있으면(KAFKA_EXPO_EVENTS_ENABLED=false) 아무것도 쓰지 않는다.
@Component
class ExpoEventSupport(
    @Value("\${expo.events.enabled:false}") private val enabled: Boolean,
    private val entityManager: EntityManager,
    private val jdbc: JdbcTemplate,
    private val expos: ExpoRepository,
    private val standards: StandardProgramRepository,
    private val trainings: TrainingProgramRepository,
    private val sessions: PreregisterSessionRepository,
    private val outbox: ExpoEventOutboxRepository,
    private val mapper: ObjectMapper,
) {
    val isEnabled: Boolean get() = enabled

    // 같은 트랜잭션에서 여러 번 불러도 박람회마다 커밋 직전 상태로 한 번만 쓴다.
    fun changed(expoId: String) {
        if (!enabled) return
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "Expo events must be recorded inside a transaction" }
        @Suppress("UNCHECKED_CAST")
        val pending =
            TransactionSynchronizationManager.getResource(RESOURCE_KEY) as MutableSet<String>?
                ?: sortedSetOf<String>().also { ids ->
                    TransactionSynchronizationManager.bindResource(RESOURCE_KEY, ids)
                    TransactionSynchronizationManager.registerSynchronization(
                        object : TransactionSynchronization {
                            override fun beforeCommit(readOnly: Boolean) {
                                entityManager.flush()
                                ids.forEach(::enqueue)
                            }

                            override fun afterCompletion(status: Int) {
                                TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE_KEY)
                            }
                        },
                    )
                }
        pending += expoId
    }

    // 트랜잭션 안에서 부른다. 박람회 행 락을 잡은 뒤 상태를 읽어 같은 박람회의 version이 커밋 순서대로 늘게 한다.
    fun enqueue(expoId: String) {
        val exists =
            jdbc.query("SELECT id FROM tb_expo WHERE id = ? FOR UPDATE", { row, _ -> row.getString(1) }, expoId).isNotEmpty()
        val expo = if (exists) expos.findByIdOrNull(expoId) else null
        if (expo == null) {
            outbox.insert(expoId, null)
            return
        }
        entityManager.refresh(expo)
        val snapshot =
            ExpoEventSnapshot.of(
                expo,
                standards.findByExpoIdOrderByIdAsc(expoId),
                trainings.findByExpoIdOrderByIdAsc(expoId),
                sessions.findAllByExpoIdOrderByStartedAtAscIdAsc(expoId),
                mapper,
            )
        outbox.insert(expoId, mapper.writeValueAsString(snapshot))
    }

    private companion object {
        val RESOURCE_KEY = Any()
    }
}
