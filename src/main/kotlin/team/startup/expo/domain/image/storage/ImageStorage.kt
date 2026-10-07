package team.startup.expo.domain.image.storage

interface ImageStorage {
    val provider: String

    fun put(
        key: String,
        bytes: ByteArray,
        contentType: String,
    )

    fun read(key: String): ByteArray

    fun delete(key: String)
}
