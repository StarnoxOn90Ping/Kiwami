package nox.kiwami.home

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import nox.kiwami.R
import nox.kiwami.databinding.DialogOfflineLaunchBinding
import nox.kiwami.isOnline
import nox.kiwami.util.setPressBounce

class OfflineLaunchDialog(
    private val activity: AppCompatActivity,
    private val onOfflineMode: () -> Unit
) {

    private var dialog: Dialog? = null
    private val animators = mutableListOf<ObjectAnimator>()
    private var spinner: ObjectAnimator? = null
    private var checking = false

    fun show() {
        if (activity.isFinishing || activity.isDestroyed) return
        val b = DialogOfflineLaunchBinding.inflate(LayoutInflater.from(activity))
        val d = Dialog(activity).apply {
            setContentView(b.root)
            setCancelable(true)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setDimAmount(0.6f)
            }
            setOnDismissListener { stopAnimations() }
        }
        dialog = d

        b.offlineTryAgain.setPressBounce(0.95f)
        b.offlineCachedOption.setPressBounce(0.95f)
        b.offlineDownloadsOption.setPressBounce(0.95f)

        b.offlineTryAgain.setOnClickListener { tryAgain(b) }
        b.offlineCachedOption.setOnClickListener { d.dismiss() }
        b.offlineDownloadsOption.setOnClickListener {
            d.dismiss()
            onOfflineMode()
        }

        b.offlineCard.apply {
            alpha = 0f
            scaleX = 0.88f
            scaleY = 0.88f
            translationY = 40f * resources.displayMetrics.density
        }
        d.show()
        b.offlineCard.animate()
            .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(420L)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()

        startAnimations(b)
    }

    fun dismiss() {
        stopAnimations()
        runCatching { dialog?.dismiss() }
        dialog = null
    }

    private fun startAnimations(b: DialogOfflineLaunchBinding) {
        listOf(b.offlineRingOne to 0L, b.offlineRingTwo to 900L).forEach { (ring, delay) ->
            ring.alpha = 0f
            animators += ObjectAnimator.ofPropertyValuesHolder(
                ring,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.45f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.45f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.65f, 0f)
            ).apply {
                duration = 1800L
                startDelay = delay
                repeatCount = ObjectAnimator.INFINITE
                interpolator = DecelerateInterpolator()
                start()
            }
        }
        animators += ObjectAnimator.ofPropertyValuesHolder(
            b.offlineIconDisc,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f)
        ).apply {
            duration = 1500L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        animators += ObjectAnimator.ofFloat(b.offlineDialogIcon, View.ALPHA, 1f, 0.65f).apply {
            duration = 1500L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopAnimations() {
        animators.forEach { it.cancel() }
        animators.clear()
        spinner?.cancel()
        spinner = null
    }

    private fun tryAgain(b: DialogOfflineLaunchBinding) {
        if (checking) return
        checking = true
        b.offlineTryAgainText.setText(R.string.offline_dialog_checking)
        spinner = ObjectAnimator.ofFloat(b.offlineTryAgainIcon, View.ROTATION, 0f, 360f).apply {
            duration = 700L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        b.offlineTryAgain.postDelayed({
            checking = false
            spinner?.cancel()
            spinner = null
            b.offlineTryAgainIcon.rotation = 0f
            if (activity.isFinishing || activity.isDestroyed) return@postDelayed
            if (isOnline(activity)) {
                dismiss()
                activity.recreate()
            } else {
                b.offlineTryAgain.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                b.offlineTryAgainText.setText(R.string.offline_dialog_still_offline)
                shake(b.offlineCard)
                b.offlineTryAgain.postDelayed({
                    b.offlineTryAgainText.setText(R.string.offline_dialog_try_again)
                }, 1500L)
            }
        }, 900L)
    }

    private fun shake(v: View) {
        val d = 10f * v.resources.displayMetrics.density
        ObjectAnimator.ofFloat(
            v, View.TRANSLATION_X, 0f, -d, d, -d * 0.75f, d * 0.75f, -d * 0.4f, d * 0.4f, 0f
        ).apply {
            duration = 750L
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }
}
