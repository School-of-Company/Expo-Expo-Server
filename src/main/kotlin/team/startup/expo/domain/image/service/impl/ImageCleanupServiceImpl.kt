package team.startup.expo.domain.image.service.impl

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import team.startup.expo.domain.image.repository.ExpoImageRepository
import team.startup.expo.domain.image.service.ImageCleanupService
import team.startup.expo.domain.image.storage.ImageStorage
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class ImageCleanupServiceImpl(
    private val imageRepository: ExpoImageRepository,
    storages: List<ImageStorage>,
    @Value("\${image.cleanup.batch-size}") private val batchSize: Int,
) : ImageCleanupService {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val storageByProvider = storages.associateBy { it.provider }

    @Scheduled(fixedDelayString = "\${image.cleanup.delay-ms}")
    override fun execute() {
        val cutoff = Instant.now().minus(RETENTION_HOURS, ChronoUnit.HOURS)
        imageRepository.findCleanupIds(cutoff, PageRequest.of(0, batchSize)).forEach { id ->
            if (imageRepository.markDeleting(id, cutoff) != 1) return@forEach
            val image = imageRepository.findByIdOrNull(id) ?: return@forEach
            try {
                checkNotNull(storageByProvider[image.storageProvider]).delete(image.objectKey)
                imageRepository.deleteById(id)
            } catch (exception: Exception) {
                logger.error("Image cleanup failed: {}", id, exception)
            }
        }
    }

    private companion object {
        const val RETENTION_HOURS = 24L
    }
}
