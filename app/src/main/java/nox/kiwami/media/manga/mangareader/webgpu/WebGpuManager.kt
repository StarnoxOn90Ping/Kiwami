package nox.kiwami.media.manga.mangareader.webgpu

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import ca.mpreg.webgpuviewer.ImageView
import ca.mpreg.webgpuviewer.ImageViewContinuous
import ca.mpreg.webgpuviewer.transition.Transition
import ca.mpreg.webgpuviewer.transition.TransitionBasic
import ca.mpreg.webgpuviewer.transition.TransitionCube
import ca.mpreg.webgpuviewer.transition.TransitionFade
import ca.mpreg.webgpuviewer.transition.TransitionFlip
import ca.mpreg.webgpuviewer.transition.TransitionNone
import ca.mpreg.webgpuviewer.transition.TransitionSphere
import ca.mpreg.webgpuviewer.transition.TransitionStackDown
import ca.mpreg.webgpuviewer.transition.TransitionStackLeft
import ca.mpreg.webgpuviewer.transition.TransitionStackRight
import ca.mpreg.webgpuviewer.transition.TransitionStackUp

object WebGpuManager {

    fun isSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val pm = context.packageManager
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x401000)
        } catch (_: Throwable) {
            false
        }
    }

    fun isEnabled(context: Context): Boolean {
        if (!isSupported(context)) return false
        return try {
            nox.kiwami.settings.saving.PrefManager.getVal<Boolean>(nox.kiwami.settings.saving.PrefName.WebGpuRenderer)
        } catch (_: Throwable) {
            false
        }
    }

    fun createPagedViewer(context: Context, isVertical: Boolean = false, isReversed: Boolean = false): ImageView? {
        if (!isSupported(context)) return null
        return try {
            ImageView(context, isVertical = isVertical, isReversed = isReversed)
        } catch (_: Throwable) {
            null
        }
    }

    fun createContinuousViewer(context: Context): ImageViewContinuous? {
        if (!isSupported(context)) return null
        return try {
            ImageViewContinuous(context)
        } catch (_: Throwable) {
            null
        }
    }

    fun getTransition(name: String, isVertical: Boolean = false): Transition {
        return when (name.lowercase()) {
            "cube" -> TransitionCube
            "flip" -> TransitionFlip
            "sphere" -> TransitionSphere
            "fade" -> TransitionFade
            "stack_left" -> TransitionStackLeft
            "stack_right" -> TransitionStackRight
            "stack_up" -> TransitionStackUp
            "stack_down" -> TransitionStackDown
            "none" -> TransitionNone
            else -> if (isVertical) TransitionBasic.Vertical else TransitionBasic
        }
    }
}
