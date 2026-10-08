package team.startup.expo.support

import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

class TrainingApplicationStub {
    private val mapper = JsonMapper.builder().build()
    private val receipts = mutableMapOf<String, Pair<String, String>>()
    var version = 0L
        private set
    var programIds = emptySet<Long>()
        private set
    var fullProgramIds = emptySet<Long>()
    var deletedProgramIds = emptySet<Long>()
    var hiddenReceipts = false
    var receiptOverride: String? = null
    val commands = mutableListOf<String>()

    @Synchronized
    fun reset() {
        receipts.clear()
        version = 0
        programIds = emptySet()
        fullProgramIds = emptySet()
        deletedProgramIds = emptySet()
        hiddenReceipts = false
        receiptOverride = null
        commands.clear()
    }

    @Synchronized
    fun cancel() {
        if (programIds.isNotEmpty()) version++
        programIds = emptySet()
    }

    @Synchronized
    fun delete(id: Long) {
        deletedProgramIds += id
        if (id in programIds) {
            version++
            programIds -= id
        }
    }

    @Synchronized
    fun receipt(id: String): String? = if (hiddenReceipts) null else receiptOverride ?: receipts[id]?.second

    @Synchronized
    fun execute(
        body: String,
        replacement: Boolean,
        forcedStatus: Int = if (replacement) 204 else 201,
    ): Int {
        commands.add(body)
        val json = mapper.readTree(body)
        val id = UUID.fromString(json.path("operationId").asString()).toString()
        val type = if (replacement) "REPLACE" else "ADD"
        val identity = "$type:$body"
        val stored = receipts[id]
        if (stored != null) return if (stored.first == identity) (if (replacement) 204 else 201) else 409
        if (forcedStatus !in setOf(201, 204)) return forcedStatus
        if (json.path("expectedVersion").asLong() != version) return 409
        val programs = json.path("programs")
        val selected = (0 until programs.size()).map { programs[it].path("id").asLong() }.toSet()
        if (selected.any { it in deletedProgramIds || (it in fullProgramIds && it !in programIds) } ||
            (!replacement && selected.any { it in programIds })
        ) {
            return 409
        }
        val next = if (replacement) selected else programIds + selected
        val changed = next != programIds
        if (changed) version++
        programIds = next
        val trainee = json.path("trainee")
        val receipt =
            mapper.writeValueAsString(
                mapOf(
                    "operationId" to id,
                    "operationType" to type,
                    "expoId" to trainee.path("expoId").asString(),
                    "traineeId" to trainee.path("id").asLong(),
                    "version" to version,
                    "changed" to changed,
                    "programIds" to programIds.sorted(),
                    "completedAt" to Instant.now().toString(),
                    "status" to "SUCCEEDED",
                ),
            )
        receipts[id] = identity to receipt
        return if (replacement) 204 else 201
    }
}
