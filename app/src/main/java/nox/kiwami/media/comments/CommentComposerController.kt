package nox.kiwami.media.comments

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import nox.kiwami.databinding.ActivityMediaBinding

class CommentComposerController(private val binding: ActivityMediaBinding) {

    private val input = binding.commentInput
    private val counterColor = binding.commentCounterText.currentTextColor
    private var sendReady: Boolean? = null

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable?) {
            update(s?.length ?: 0, true)
        }
    }

    fun bind() {
        input.addTextChangedListener(watcher)
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) expand() else if (input.text.isNullOrEmpty()) collapse()
        }
        update(input.text?.length ?: 0, false)
        if (input.hasFocus()) expand()
    }

    fun release() {
        input.removeTextChangedListener(watcher)
        input.onFocusChangeListener = null
    }

    private fun expand() {
        val row = binding.commentActionRow
        if (row.visibility == View.VISIBLE) return
        row.visibility = View.VISIBLE
        val offset = 10f * binding.root.resources.displayMetrics.density
        listOf<View>(
            binding.commentSpoiler,
            binding.commentGif,
            binding.commentLabel,
            binding.commentCounterText
        ).forEachIndexed { index, view ->
            view.animate().cancel()
            view.scaleX = 0.5f
            view.scaleY = 0.5f
            view.translationY = offset
            view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setStartDelay(90L + index * 50L)
                .setDuration(420)
                .setInterpolator(OvershootInterpolator(2.2f))
                .start()
        }
    }

    private fun collapse() {
        binding.commentActionRow.visibility = View.GONE
    }

    private fun update(length: Int, animate: Boolean) {
        binding.commentCounterRing.setProgress(length / 300f, animate)
        binding.commentCounterText.text = "$length/300"
        binding.commentCounterText.setTextColor(
            if (length >= 280) 0xFFE5484D.toInt() else counterColor
        )
        val ready = length > 0
        if (sendReady == ready) return
        sendReady = ready
        val send = binding.commentSend
        val scale = if (ready) 1f else 0.88f
        val alpha = if (ready) 1f else 0.4f
        if (!animate) {
            send.scaleX = scale
            send.scaleY = scale
            send.alpha = alpha
            return
        }
        send.animate()
            .scaleX(scale)
            .scaleY(scale)
            .alpha(alpha)
            .setDuration(if (ready) 320L else 180L)
            .setInterpolator(if (ready) OvershootInterpolator(3f) else DecelerateInterpolator())
            .start()
    }
}
