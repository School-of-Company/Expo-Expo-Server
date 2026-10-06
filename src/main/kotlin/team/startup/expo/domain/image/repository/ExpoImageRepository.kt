package team.startup.expo.domain.image.repository

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.image.entity.ExpoImage
import java.time.Instant

interface ExpoImageRepository : JpaRepository<ExpoImage, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select image from ExpoImage image where image.publicUrl = :url")
    fun findByPublicUrlForUpdate(
        @Param("url") url: String,
    ): ExpoImage?

    fun findByPublicUrl(url: String): ExpoImage?

    @Query(
        "select image.id from ExpoImage image where (image.status = 'PENDING' and image.createdAt < :cutoff) or (image.status = 'ORPHAN' and image.detachedAt < :cutoff) or image.status = 'DELETING'",
    )
    fun findCleanupIds(
        @Param("cutoff") cutoff: Instant,
        pageable: Pageable,
    ): List<String>

    @Transactional
    @Modifying
    @Query(
        "update ExpoImage image set image.status = 'DELETING' where image.id = :id and image.expoId is null and (image.status = 'DELETING' or (image.status = 'PENDING' and image.createdAt < :cutoff) or (image.status = 'ORPHAN' and image.detachedAt < :cutoff))",
    )
    fun markDeleting(
        @Param("id") id: String,
        @Param("cutoff") cutoff: Instant,
    ): Int
}
