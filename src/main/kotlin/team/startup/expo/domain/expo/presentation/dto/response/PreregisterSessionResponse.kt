package team.startup.expo.domain.expo.presentation.dto.response

import team.startup.expo.domain.expo.entity.PreregisterSession
import java.time.Instant

data class PreregisterSessionResponse(
    val id: Long,
    val expoId: String,
    val title: String,
    val startedAt: Instant,
    val endedAt: Instant,
    val place: String,
    val capacity: Int,
    val waitingCapacity: Int,
    val closed: Boolean,
    val revision: Long,
)

fun PreregisterSession.toResponse(): PreregisterSessionResponse =
    PreregisterSessionResponse(requireNotNull(id), expo.id, title, startedAt, endedAt, place, capacity, waitingCapacity, closed, revision)
