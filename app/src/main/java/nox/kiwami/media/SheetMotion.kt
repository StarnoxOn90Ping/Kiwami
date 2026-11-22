package nox.kiwami.media

import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

object SheetMotion {

    fun expand(dialog: Dialog) {
        if (dialog is BottomSheetDialog) {
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
        }
    }

    fun style(dialog: Dialog?) {
        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?: return
        sheet.background = SourcePicker.sheetBackground(sheet.context)
    }

    fun popInChildren(group: ViewGroup, startIndex: Int = 0) {
        var index = startIndex
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.visibility == View.VISIBLE) popIn(child, index++)
        }
    }

    fun popIn(view: View, index: Int) {
        val density = view.resources.displayMetrics.density
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = 26f * density
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(index * 40L)
            .setDuration(420)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    fun settle(view: View) {
        view.animate().cancel()
        view.alpha = 1f
        view.translationY = 0f
    }

    fun popInRows(list: RecyclerView, limit: Int = 10, offset: Int = 0) {
        var active = true
        val listener = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                if (!active) return
                val position = list.getChildAdapterPosition(view)
                if (position < 0 || position >= limit) return
                popIn(view, position + offset)
            }

            override fun onChildViewDetachedFromWindow(view: View) {
                settle(view)
            }
        }
        list.addOnChildAttachStateChangeListener(listener)
        list.postDelayed({
            active = false
            list.removeOnChildAttachStateChangeListener(listener)
        }, 900)
    }
}
