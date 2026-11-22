package nox.kiwami.media

import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import nox.kiwami.R
import nox.kiwami.util.SourceStatusView

class SourcePillController(
    private val pill: View,
    private val nameView: TextView,
    private val status: SourceStatusView,
    private val countView: TextView,
    private val recycler: RecyclerView,
    private val onOpen: () -> Unit
) {

    private val density = pill.resources.displayMetrics.density
    private var shown = false
    private var snapshot: SourceCardController.Snapshot? = null

    private val scroll = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            evaluate()
        }
    }

    fun bind() {
        recycler.addOnScrollListener(scroll)
        pill.visibility = View.GONE
        pill.setOnClickListener { open() }
        pill.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.95f).scaleY(0.95f)
                    .setDuration(90).setInterpolator(DecelerateInterpolator()).start()

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f)
                    .scaleY(1f).setDuration(300).setInterpolator(OvershootInterpolator(2.2f)).start()
            }
            false
        }
    }

    fun release() {
        recycler.removeOnScrollListener(scroll)
        pill.animate().cancel()
    }

    fun update(next: SourceCardController.Snapshot) {
        snapshot = next
        nameView.text = next.name
        status.setMode(
            when (next.phase) {
                SourceCardController.Phase.SEARCHING -> SourceStatusView.Mode.SPIN
                SourceCardController.Phase.FOUND -> SourceStatusView.Mode.CHECK
                SourceCardController.Phase.MISSING -> SourceStatusView.Mode.WARN
            },
            false
        )
        if (next.phase == SourceCardController.Phase.FOUND && next.count > 0) {
            countView.text = "· ${next.count}"
            countView.visibility = View.VISIBLE
        } else {
            countView.visibility = View.GONE
        }
        evaluate()
    }

    private fun evaluate() {
        val manager = recycler.layoutManager as? LinearLayoutManager ?: return
        val first = manager.findFirstVisibleItemPosition()
        val scrolledAway = when {
            first > 0 -> true
            first == 0 -> {
                val host = manager.findViewByPosition(0)?.findViewById<View>(R.id.sourceCardHost)
                if (host == null) {
                    false
                } else {
                    val hostAt = IntArray(2)
                    host.getLocationInWindow(hostAt)
                    val listAt = IntArray(2)
                    recycler.getLocationInWindow(listAt)
                    hostAt[1] + host.height < listAt[1] + 8 * density
                }
            }

            else -> false
        }
        setShown(scrolledAway && snapshot != null)
    }

    private fun setShown(show: Boolean) {
        if (show == shown) return
        shown = show
        pill.animate().cancel()
        if (show) {
            pill.visibility = View.VISIBLE
            pill.alpha = 0f
            pill.translationY = -48f * density
            pill.animate().alpha(1f).translationY(0f).setDuration(460)
                .setInterpolator(OvershootInterpolator(1.4f)).start()
        } else {
            pill.animate().alpha(0f).translationY(-32f * density).setDuration(200)
                .withEndAction { if (!shown) pill.visibility = View.GONE }.start()
        }
    }

    private fun open() {
        val opener = object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                recyclerView.removeOnScrollListener(this)
                onOpen()
            }
        }
        recycler.addOnScrollListener(opener)
        recycler.scrollToPosition(8)
        recycler.smoothScrollToPosition(0)
        recycler.postDelayed({ recycler.removeOnScrollListener(opener) }, 5000)
    }
}
