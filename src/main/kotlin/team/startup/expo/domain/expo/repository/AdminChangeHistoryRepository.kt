package team.startup.expo.domain.expo.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Repository
import team.startup.expo.domain.expo.presentation.dto.request.AdminChangeHistoryRequest
import team.startup.expo.domain.expo.presentation.dto.response.AdminChangeHistoryItem
import team.startup.expo.domain.expo.presentation.dto.response.AdminChangeHistoryPage

@Repository
class AdminChangeHistoryRepository(
    private val jdbc: JdbcTemplate,
) {
    fun currentAdminId(): Long? =
        (SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken)
            ?.token
            ?.subject
            ?.toLongOrNull()

    fun record(
        expoId: String,
        type: String,
        targetType: String,
        targetId: Long,
        summary: String,
        adminId: Long?,
    ) {
        jdbc.update(
            "INSERT INTO tb_admin_change_history(expo_id,type,target_type,target_id,summary,admin_id) VALUES (?,?,?,?,?,?)",
            expoId,
            type,
            targetType,
            targetId,
            summary,
            adminId,
        )
    }

    fun page(
        expoId: String,
        request: AdminChangeHistoryRequest,
    ): AdminChangeHistoryPage {
        val arguments = mutableListOf<Any>(expoId)
        val filters = StringBuilder("expo_id=?")
        request.type?.let {
            filters.append(" AND type=?")
            arguments.add(it.name)
        }
        request.from?.let {
            filters.append(" AND occurred_at>=?")
            arguments.add(java.sql.Timestamp.from(it))
        }
        request.to?.let {
            filters.append(" AND occurred_at<?")
            arguments.add(java.sql.Timestamp.from(it))
        }
        val total =
            jdbc.queryForObject(
                "SELECT count(*) FROM tb_admin_change_history WHERE $filters",
                Long::class.java,
                *arguments.toTypedArray(),
            )!!
        val items =
            jdbc.query(
                "SELECT * FROM tb_admin_change_history WHERE $filters ORDER BY occurred_at DESC,id DESC LIMIT ? OFFSET ?",
                { row, _ ->
                    AdminChangeHistoryItem(
                        row.getLong("id"),
                        row.getString("type"),
                        row.getObject("admin_id", Long::class.javaObjectType),
                        row.getTimestamp("occurred_at").toInstant(),
                        row.getString("target_type"),
                        row.getLong("target_id"),
                        row.getString("summary"),
                    )
                },
                *(arguments + listOf(request.size, request.page.toLong() * request.size)).toTypedArray(),
            )
        return AdminChangeHistoryPage(expoId, items, request.page, request.size, total)
    }
}
