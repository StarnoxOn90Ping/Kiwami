package nox.kiwami.download.manga

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import nox.kiwami.R
import nox.kiwami.loadImage
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName


class OfflineMangaAdapter(
    private val context: Context,
    private var items: List<OfflineMangaModel>,
    private val searchListener: OfflineMangaSearchListener
) : BaseAdapter() {
    private val inflater: LayoutInflater =
        context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
    private var originalItems: List<OfflineMangaModel> = items
    private var style: Int = PrefManager.getVal(PrefName.OfflineView)

    override fun getCount(): Int {
        return items.size
    }

    override fun getItem(position: Int): Any {
        return items[position]
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {

        val view: View = convertView ?: when (style) {
            0 -> inflater.inflate(R.layout.item_media_large, parent, false)
            1 -> inflater.inflate(R.layout.item_media_compact, parent, false)
            else -> inflater.inflate(R.layout.item_media_compact, parent, false)
        }

        val item = getItem(position) as OfflineMangaModel
        val imageView = view.findViewById<ImageView>(R.id.itemCompactImage)
        val titleTextView = view.findViewById<TextView>(R.id.itemCompactTitle)
        val itemScore = view.findViewById<TextView>(R.id.itemCompactScore)
        val ongoing = view.findViewById<CardView>(R.id.itemCompactOngoing)
        val totalChapter = view.findViewById<TextView>(R.id.itemCompactTotal)

        if (style == 0) {
            val bannerView = view.findViewById<ImageView>(R.id.itemCompactBanner)
            val chapters = view.findViewById<TextView>(R.id.itemTotal)
            val text = " ${context.getString(R.string.chapters)}"
            chapters.text = text
            bannerView.loadImage(item.banner?.toString() ?: item.image?.toString())
            totalChapter.text = item.totalChapter
        } else if (style == 1) {
            val readChapter =
                view.findViewById<TextView>(R.id.itemCompactUserProgress)
            readChapter.text = item.readChapter
            totalChapter.text = context.getString(R.string.total_divider, item.totalChapter)
        }

        imageView.loadImage(item.image?.toString())
        titleTextView.text = item.title
        itemScore.text = item.score

        if (item.isOngoing) {
            ongoing.visibility = View.VISIBLE
        } else {
            ongoing.visibility = View.GONE
        }
        return view
    }

    private var query: String = ""

    fun onSearchQuery(query: String) {
        this.query = query
        applyFilter()
    }

    fun setItems(items: List<OfflineMangaModel>) {
        this.originalItems = items
        applyFilter()
    }

    private fun applyFilter() {
        items = if (query.isEmpty()) originalItems
        else originalItems.filter { it.title.contains(query, ignoreCase = true) }
        notifyDataSetChanged()
    }

    fun notifyNewGrid() {
        style = PrefManager.getVal(PrefName.OfflineView)
        notifyDataSetChanged()
    }
}