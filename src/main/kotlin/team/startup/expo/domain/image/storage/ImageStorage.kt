package team.startup.expo.domain.image.storage

import org.springframework.core.io.Resource

interface ImageStorage {
    val provider: String

    fun put(
        key: String,
        bytes: ByteArray,
        contentType: String,
    )

    fun read(key: String): Resource

    fun delete(key: String)
}
