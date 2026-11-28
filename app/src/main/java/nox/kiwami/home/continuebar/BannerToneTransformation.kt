package nox.kiwami.home.continuebar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

class BannerToneTransformation(
    private val targetLuma: Float,
    private val saturation: Float
) : BitmapTransformation() {

    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int
    ): Bitmap {
        val width = toTransform.width
        val height = toTransform.height
        val pixels = IntArray(width * height)
        toTransform.getPixels(pixels, 0, width, 0, 0, width, height)
        var sum = 0f
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            sum += 0.299f * r + 0.587f * g + 0.114f * b
        }
        val mean = (sum / pixels.size / 255f).coerceAtLeast(MIN_MEAN)
        val gain = (targetLuma / mean).coerceIn(MIN_GAIN, MAX_GAIN)

        val matrix = ColorMatrix().apply { setSaturation(saturation) }
        matrix.postConcat(ColorMatrix().apply { setScale(gain, gain, gain, 1f) })

        val result = pool.get(width, height, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(
            toTransform,
            0f,
            0f,
            Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
        )
        return result
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("$ID:$targetLuma:$saturation".toByteArray(CHARSET))
    }

    override fun equals(other: Any?): Boolean =
        other is BannerToneTransformation &&
            other.targetLuma == targetLuma &&
            other.saturation == saturation

    override fun hashCode(): Int = ID.hashCode() + 31 * targetLuma.hashCode() + saturation.hashCode()

    private companion object {
        const val ID = "nox.kiwami.home.continuebar.BannerToneTransformation"
        const val MIN_MEAN = 0.04f
        const val MIN_GAIN = 0.2f
        const val MAX_GAIN = 1.6f
    }
}
