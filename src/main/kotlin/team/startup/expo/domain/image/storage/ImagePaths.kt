package team.startup.expo.domain.image.storage

object ImagePaths {
    const val RESOURCE_PATH = "/image"
    private const val KEY_PREFIX = "img/"
    private val KEY_PATTERN = Regex("${KEY_PREFIX}[0-9a-f-]{36}\\.(jpg|png)")

    fun objectKey(
        id: String,
        extension: String,
    ): String = "$KEY_PREFIX$id.$extension"

    fun publicUrl(
        baseUrl: String,
        id: String,
    ): String = "${baseUrl.trimEnd('/')}$RESOURCE_PATH/$id"

    fun isValidKey(key: String): Boolean = KEY_PATTERN.matches(key)
}
