package nox.kiwami.download

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentActivity
import nox.kiwami.R
import nox.kiwami.media.MediaSingleton
import kotlin.math.max
import kotlin.math.min

object DownloadOpener {
    private const val MAX_SNAPSHOT = 100

    fun pressFeedback(tile: View) {
        tile.animate().cancel()
        tile.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).withEndAction {
            tile.animate().scaleX(1f).scaleY(1f)
                .setInterpolator(OvershootInterpolator(2f)).setDuration(180).start()
        }.start()
    }

    fun open(activity: FragmentActivity, tile: View, intent: Intent) {
        val cover = tile.findViewById<ImageView>(R.id.itemCompactImage)
        if (cover == null || !tile.isAttachedToWindow) {
            ContextCompat.startActivity(activity, intent, null)
            return
        }
        tile.animate().cancel()
        tile.scaleX = 1f
        tile.scaleY = 1f
        MediaSingleton.bitmap = snapshot(cover)
        val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
            activity,
            cover,
            ViewCompat.getTransitionName(cover) ?: "mediaCover"
        )
        ContextCompat.startActivity(activity, intent, options.toBundle())
    }

    private fun snapshot(view: ImageView): Bitmap? {
        val drawable = view.drawable ?: return null
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else view.width
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else view.height
        if (width <= 0 || height <= 0) return null
        val scale = min(1f, MAX_SNAPSHOT / max(width, height).toFloat())
        return runCatching {
            drawable.toBitmap(
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1)
            )
        }.getOrNull()
    }
}
