package team.startup.expo.domain.image.storage.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import team.startup.expo.domain.image.storage.ImageStorage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Component
class LocalImageStorage(
    @Value("\${image.storage.local.directory}") directory: String,
) : ImageStorage {
    override val provider: String = "local"

    private val root = Path.of(directory).toAbsolutePath().normalize()

    override fun put(
        key: String,
        bytes: ByteArray,
        contentType: String,
    ) {
        val path = pathFor(key)
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".upload-", ".tmp")
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun read(key: String): ByteArray = Files.readAllBytes(pathFor(key))

    override fun delete(key: String) {
        Files.deleteIfExists(pathFor(key))
    }

    private fun pathFor(key: String): Path {
        require(KEY_PATTERN.matches(key)) { "Invalid image key" }
        return root.resolve(key)
    }

    private companion object {
        val KEY_PATTERN = Regex("img/[0-9a-f-]{36}\\.(jpg|png)")
    }
}
