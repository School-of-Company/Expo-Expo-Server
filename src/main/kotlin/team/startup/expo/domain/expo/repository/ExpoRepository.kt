package team.startup.expo.domain.expo.repository

import jakarta.persistence.LockModeType
import jakarta.persistence.QueryHint
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.QueryHints
import org.springframework.data.repository.query.Param
import team.startup.expo.domain.expo.entity.Expo

interface ExpoRepository : JpaRepository<Expo, String> {
    fun existsByIdAndDeletingAtIsNotNull(id: String): Boolean

    fun findAllByDeletingAtIsNullAndFinishedDayGreaterThanEqual(
        finishedDay: String,
        pageable: Pageable,
    ): Slice<Expo>

    // #160: 락 대기는 5초까지만 하고 PessimisticLockingFailureException(409)으로 끝낸다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select expo from Expo expo where expo.id = :id")
    fun findLockedById(
        @Param("id") id: String,
    ): Expo?

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update Expo expo
           set expo.title = :title,
               expo.description = :description,
               expo.startedDay = :startedDay,
               expo.finishedDay = :finishedDay,
               expo.location = :location,
               expo.coverImage = :coverImage,
               expo.x = :x,
               expo.y = :y,
               expo.preregisterTeacherCapacity = :preregisterTeacherCapacity,
               expo.preregisterNonTeacherCapacity = :preregisterNonTeacherCapacity
         where expo.id = :id
        """,
    )
    fun updateInfo(
        @Param("id") id: String,
        @Param("title") title: String,
        @Param("description") description: String,
        @Param("startedDay") startedDay: String,
        @Param("finishedDay") finishedDay: String,
        @Param("location") location: String,
        @Param("coverImage") coverImage: String?,
        @Param("x") x: String,
        @Param("y") y: String,
        @Param("preregisterTeacherCapacity") preregisterTeacherCapacity: Int?,
        @Param("preregisterNonTeacherCapacity") preregisterNonTeacherCapacity: Int?,
    ): Int
}
