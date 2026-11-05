package team.startup.expo.domain.expo.repository

import com.querydsl.core.BooleanBuilder
import com.querydsl.jpa.impl.JPAQueryFactory
import team.startup.expo.domain.expo.entity.QAdminChangeHistory.adminChangeHistory
import team.startup.expo.domain.expo.presentation.dto.request.AdminChangeHistoryRequest
import team.startup.expo.domain.expo.presentation.dto.response.AdminChangeHistoryItem
import team.startup.expo.domain.expo.presentation.dto.response.AdminChangeHistoryPage

class AdminChangeHistoryQueriesImpl(
    private val queries: JPAQueryFactory,
) : AdminChangeHistoryQueries {
    override fun page(
        expoId: String,
        request: AdminChangeHistoryRequest,
    ): AdminChangeHistoryPage {
        val history = adminChangeHistory
        val filters = BooleanBuilder(history.expoId.eq(expoId))
        request.type?.let { filters.and(history.type.eq(it)) }
        request.from?.let { filters.and(history.occurredAt.goe(it)) }
        request.to?.let { filters.and(history.occurredAt.lt(it)) }
        val total =
            requireNotNull(
                queries
                    .select(history.count())
                    .from(history)
                    .where(filters)
                    .fetchOne(),
            )
        val items =
            queries
                .selectFrom(history)
                .where(filters)
                .orderBy(history.occurredAt.desc(), history.id.desc())
                .limit(request.size.toLong())
                .offset(request.page.toLong() * request.size)
                .fetch()
                .map {
                    AdminChangeHistoryItem(
                        it.id,
                        it.type.name,
                        it.adminId,
                        requireNotNull(it.occurredAt),
                        it.targetType.name,
                        it.targetId,
                        it.summary,
                    )
                }
        return AdminChangeHistoryPage(expoId, items, request.page, request.size, total)
    }
}
