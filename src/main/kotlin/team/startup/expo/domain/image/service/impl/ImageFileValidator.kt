package team.startup.expo.domain.image.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.multipart.MultipartFile
import team.startup.expo.global.exception.ExpectedException
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

@Component
class ImageFileValidator {
    fun validateAndEncode(file: MultipartFile): ValidatedImage {
        val format =
            when (file.contentType) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                else -> throw ExpectedException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "JPEG 또는 PNG 이미지만 업로드할 수 있습니다.")
            }
        val extension = file.originalFilename?.substringAfterLast('.', "")?.lowercase()
        if (extension !in if (format == "jpg") setOf("jpg", "jpeg") else setOf("png")) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "이미지 형식이 일치하지 않습니다.")
        }
        if (file.isEmpty || file.size > MAX_BYTES) {
            throw ExpectedException(
                if (file.size >
                    MAX_BYTES
                ) {
                    HttpStatus.PAYLOAD_TOO_LARGE
                } else {
                    HttpStatus.BAD_REQUEST
                },
                "이미지 크기가 올바르지 않습니다.",
            )
        }
        try {
            val bytes = file.bytes
            val signatureValid =
                if (format == "jpg") {
                    bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
                } else {
                    bytes.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }
                }
            if (!signatureValid) throw invalidImage()

            val image =
                MemoryCacheImageInputStream(bytes.inputStream()).use { input ->
                    val readers = ImageIO.getImageReaders(input)
                    if (!readers.hasNext()) throw invalidImage()
                    val reader = readers.next()
                    try {
                        reader.input = input
                        val width = reader.getWidth(0)
                        val height = reader.getHeight(0)
                        if (width < 750 || height < 360 || width > 8000 || height > 8000 || width.toLong() * height > 20_000_000) {
                            throw invalidImage()
                        }
                        reader.read(0) ?: throw invalidImage()
                    } finally {
                        reader.dispose()
                    }
                }

            val output = ByteArrayOutputStream()
            val encodedImage =
                if (format == "jpg") {
                    BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB).also { rgb ->
                        val graphics = rgb.createGraphics()
                        try {
                            graphics.color = Color.WHITE
                            graphics.fillRect(0, 0, image.width, image.height)
                            graphics.drawImage(image, 0, 0, null)
                        } finally {
                            graphics.dispose()
                        }
                    }
                } else {
                    image
                }
            if (!ImageIO.write(encodedImage, format, output)) throw invalidImage()
            val encoded = output.toByteArray()
            if (encoded.size > MAX_BYTES) throw ExpectedException(HttpStatus.PAYLOAD_TOO_LARGE, "이미지 크기가 너무 큽니다.")
            return ValidatedImage(encoded, file.contentType!!, format)
        } catch (exception: ExpectedException) {
            throw exception
        } catch (exception: IOException) {
            throw invalidImage()
        } catch (exception: IndexOutOfBoundsException) {
            throw invalidImage()
        }
    }

    private fun invalidImage() = ExpectedException(HttpStatus.BAD_REQUEST, "올바른 이미지가 아닙니다.")

    private companion object {
        const val MAX_BYTES = 5 * 1024 * 1024
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}

data class ValidatedImage(
    val bytes: ByteArray,
    val contentType: String,
    val extension: String,
)
