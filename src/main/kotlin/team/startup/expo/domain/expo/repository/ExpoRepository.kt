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
    @Query("SELECT id FROM tb_expo WHERE id=:expoId FOR UPDATE", nativeQuery = true)
    fun lockEventId(expoId: String): String?

    @Query("SELECT deleting_at IS NOT NULL FROM tb_expo WHERE id=:expoId FOR UPDATE", nativeQuery = true)
    fun lockTrainingApplication(expoId: String): Boolean?

    @Query(
        """SELECT jsonb_build_object(
            'title',title,'description',description,'startedDay',started_day,'finishedDay',finished_day,
            'location',location,'coverImage',cover_image,'x',x,'y',y,
            'preregisterTeacherCapacity',preregister_teacher_capacity,'preregisterNonTeacherCapacity',preregister_non_teacher_capacity,
            'participantPhoneVerificationRequired',participant_phone_verification_required,
            'participantPhoneVerificationRevision',participant_phone_verification_revision,
            'deleting',deleting_at IS NOT NULL,
            'programChangePending',EXISTS(SELECT 1 FROM tb_program_change WHERE expo_id=:expoId)
        )::text FROM tb_expo WHERE id=:expoId""",
        nativeQuery = true,
    )
    fun eventState(expoId: String): String?

    @Query("select expo.id from Expo expo where expo.id > :after order by expo.id")
    fun findIdsAfter(
        after: String,
        pageable: Pageable,
    ): List<String>

    @Query("select expo.id from Expo expo")
    fun findAllIds(): List<String>

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
