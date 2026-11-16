package nox.kiwami.offline

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import nox.kiwami.R

class OfflineEmptyState(root: View) {
    private val container: View = root.findViewById(R.id.offlineEmptyState)
    private val icon: ImageView = root.findViewById(R.id.offlineEmptyIcon)
    private val title: TextView = root.findViewById(R.id.offlineEmptyTitle)
    private val subtitle: TextView = root.findViewById(R.id.offlineEmptySubtitle)

    fun show(@DrawableRes iconRes: Int, titleText: String, subtitleText: String) {
        icon.setImageResource(iconRes)
        title.text = titleText
        subtitle.text = subtitleText
        container.visibility = View.VISIBLE
    }

    fun hide() {
        container.visibility = View.GONE
    }
}
