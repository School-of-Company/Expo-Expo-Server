package team.startup.expo.domain.image.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "tb_expo_image")
class ExpoImage(
    @field:Id
    @field:Column(nullable = false, length = 36)
    val id: String,

    @field:Column(name = "storage_provider", nullable = false, length = 30)
    val storageProvider: String,

    @field:Column(name = "object_key", nullable = false, unique = true, length = 100)
    val objectKey: String,

    @field:Column(name = "public_url", nullable = false, unique = true, columnDefinition = "TEXT")
    val publicUrl: String,

    @field:Column(name = "content_type", nullable = false, length = 30)
    val contentType: String,

    @field:Column(name = "uploaded_by", nullable = false, length = 255)
    val uploadedBy: String,

    @field:Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    @field:Column(nullable = false, length = 20)
    var status: String = PENDING
        protected set

    @field:Column(name = "expo_id", length = 36)
    var expoId: String? = null
        protected set

    @field:Column(name = "attached_at")
    var attachedAt: Instant? = null
        protected set

    @field:Column(name = "detached_at")
    var detachedAt: Instant? = null
        protected set

    fun attach(expoId: String) {
        status = ATTACHED
        this.expoId = expoId
        attachedAt = Instant.now()
    }

    fun detach() {
        status = ORPHAN
        expoId = null
        detachedAt = Instant.now()
    }

    companion object {
        const val PENDING = "PENDING"
        const val ATTACHED = "ATTACHED"
        const val ORPHAN = "ORPHAN"
        const val DELETING = "DELETING"
    }
}
