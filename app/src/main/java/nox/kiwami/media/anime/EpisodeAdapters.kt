package nox.kiwami.media.anime

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import androidx.annotation.OptIn
import androidx.core.view.isVisible
import androidx.lifecycle.coroutineScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.RecyclerView
import nox.kiwami.R
import nox.kiwami.connections.updateProgress
import nox.kiwami.databinding.ItemEpisodeCompactBinding
import nox.kiwami.databinding.ItemEpisodeGridBinding
import nox.kiwami.databinding.ItemEpisodeListBinding
import nox.kiwami.media.Media
import nox.kiwami.media.MediaNameAdapter
import nox.kiwami.setAnimation
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.util.customAlertDialog
import nox.kiwami.util.SizeFormatter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.widget.NumberPicker
import nox.kiwami.currContext
import nox.kiwami.download.anime.AnimeDownloader

fun handleProgress(cont: LinearLayout, bar: View, empty: View, mediaId: Int, ep: String) {
    val cleanNum = MediaNameAdapter.findChapterNumber(ep)?.let {
        if (it % 1 == 0f) it.toInt().toString() else it.toString()
    } ?: MediaNameAdapter.findEpisodeNumber(ep)?.let {
        if (it % 1 == 0f) it.toInt().toString() else it.toString()
    }
    val curr = PrefManager.getNullableCustomVal("${mediaId}_${ep}", null, Long::class.java)
        ?: cleanNum?.let { PrefManager.getNullableCustomVal("${mediaId}_${it}", null, Long::class.java) }
    val max = PrefManager.getNullableCustomVal("${mediaId}_${ep}_max", null, Long::class.java)
        ?: cleanNum?.let { PrefManager.getNullableCustomVal("${mediaId}_${it}_max", null, Long::class.java) }
    if (curr != null && max != null && max > 0L) {
        cont.visibility = View.VISIBLE
        val div = (curr.toFloat() / max.toFloat()).coerceIn(0f, 1f)
        val barParams = bar.layoutParams as LinearLayout.LayoutParams
        barParams.weight = div
        bar.layoutParams = barParams
        val params = empty.layoutParams as LinearLayout.LayoutParams
        params.weight = 1f - div
        empty.layoutParams = params
    } else {
        cont.visibility = View.GONE
    }
}

@OptIn(UnstableApi::class)
class EpisodeAdapter(
    private var type: Int,
    private val media: Media,
    private val fragment: AnimeWatchFragment,
    var arr: List<Episode> = arrayListOf(),
    var offlineMode: Boolean
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    val context = fragment.requireContext()

    companion object {
        private const val PAYLOAD_PROGRESS = "download_progress"
        private const val PAYLOAD_DOWNLOAD_STATE = "download_state"
        private const val PAYLOAD_SELECTION = "selection"
        private const val PAYLOAD_WATCHED = "watched_state"
    }

    val selectedEpisodes = LinkedHashSet<String>()
    var onSelectionChanged: ((Int) -> Unit)? = null

    fun isSelecting(): Boolean = selectedEpisodes.isNotEmpty()

    fun isDownloaded(episodeNumber: String): Boolean = downloadedEpisodes.contains(episodeNumber)

    fun toggleSelection(episodeNumber: String) {
        if (!selectedEpisodes.remove(episodeNumber)) selectedEpisodes.add(episodeNumber)
        val index = arr.indexOfFirst { it.number == episodeNumber }
        if (index >= 0) notifyItemChanged(index, PAYLOAD_SELECTION)
        onSelectionChanged?.invoke(selectedEpisodes.size)
    }

    fun selectAll() {
        arr.forEach { selectedEpisodes.add(it.number) }
        notifyItemRangeChanged(0, arr.size, PAYLOAD_SELECTION)
        onSelectionChanged?.invoke(selectedEpisodes.size)
    }

    fun clearSelection() {
        if (selectedEpisodes.isEmpty()) return
        selectedEpisodes.clear()
        notifyItemRangeChanged(0, arr.size, PAYLOAD_SELECTION)
        onSelectionChanged?.invoke(0)
    }

    private fun applySelection(holder: RecyclerView.ViewHolder, ep: Episode, animate: Boolean) {
        val card: android.widget.FrameLayout = when (holder) {
            is EpisodeListViewHolder -> holder.binding.itemEpisodeCont
            is EpisodeGridViewHolder -> holder.binding.itemEpisodeCont
            is EpisodeCompactViewHolder -> holder.binding.itemEpisodeCont
            else -> return
        }
        applySelectionStyle(card, selectedEpisodes.contains(ep.number), animate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return (when (viewType) {
            0 -> EpisodeListViewHolder(
                ItemEpisodeListBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            1 -> EpisodeGridViewHolder(
                ItemEpisodeGridBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            2 -> EpisodeCompactViewHolder(
                ItemEpisodeCompactBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            else -> throw IllegalArgumentException()
        })
    }

    override fun getItemViewType(position: Int): Int {
        return type
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        if (position !in arr.indices) return
        val ep = arr[position]
        var handled = false
        if (payloads.contains(PAYLOAD_SELECTION)) {
            applySelection(holder, ep, true)
            handled = true
        }
        if (payloads.contains(PAYLOAD_WATCHED)) {
            bindWatchState(holder, ep)
            handled = true
        }
        if (handled) return
        val listHolder = holder as? EpisodeListViewHolder ?: return

        when {
            payloads.contains(PAYLOAD_PROGRESS) -> {
                listHolder.bindProgressText(ep.downloadProgress)
            }
            payloads.contains(PAYLOAD_DOWNLOAD_STATE) -> {
                listHolder.bind(ep.number, ep.downloadProgress, ep.desc)
            }
            else -> super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val ep = arr[position]
        resetWatchAnim(holder)
        boundKeys[ep.number] = contentKey(ep)
        val isTorrent = ep.extra?.containsKey("torrentHash") == true || ep.link?.contains("127.0.0.1:8090") == true
        val title = if (isTorrent) {
            ep.title ?: ep.sEpisode?.name ?: ep.number
        } else if (!ep.title.isNullOrEmpty() && ep.title != "null") {
            ep.title?.let { MediaNameAdapter.removeEpisodeNumber(it) }
        } else {
            ep.number
        } ?: ""

        when (holder) {
            is EpisodeListViewHolder -> {
                val binding = holder.binding
                setAnimation(fragment.requireContext(), holder.binding.root)

                val thumb = ep.thumb?.let {
                    if (it.url.isNotEmpty()) {
                        if (it.url.startsWith("content://") || it.url.startsWith("file://")) {
                            it.url
                        } else {
                            GlideUrl(it.url) { it.headers }
                        }
                    } else null
                }
                Glide.with(binding.itemMediaImage).load(thumb ?: media.cover).override(400, 0)
                    .into(binding.itemMediaImage)
                binding.itemEpisodeNumber.text = ep.number
                binding.itemEpisodeTitle.text = if (ep.number == title) "Episode $title" else title

                if (ep.filler) {
                    binding.itemEpisodeFiller.visibility = View.VISIBLE
                    binding.itemEpisodeFillerView.visibility = View.VISIBLE
                } else {
                    binding.itemEpisodeFiller.visibility = View.GONE
                    binding.itemEpisodeFillerView.visibility = View.GONE
                }

                if (ep.rating != null) {
                    binding.itemEpisodeRating.visibility = View.VISIBLE
                    binding.itemEpisodeRating.text = "★ ${ep.rating}"
                } else {
                    binding.itemEpisodeRating.visibility = View.GONE
                }

                if (ep.date != null) {
                    binding.itemEpisodeDate.visibility = View.VISIBLE
                    binding.itemEpisodeDate.text = ep.date
                } else {
                    binding.itemEpisodeDate.visibility = View.GONE
                }

                binding.itemEpisodeDesc.isVisible = !ep.desc.isNullOrBlank()
                binding.itemEpisodeDesc.text = ep.desc ?: ""
                holder.bind(ep.number, ep.downloadProgress, ep.desc)

                val epNum = MediaNameAdapter.findEpisodeNumber(ep.number) ?: ep.number.toFloatOrNull() ?: 9999f
                if (media.userProgress != null) {
                    if (epNum <= media.userProgress!!.toFloat()) {
                        binding.itemEpisodeViewedCover.visibility = View.VISIBLE
                        binding.itemEpisodeViewed.visibility = View.VISIBLE
                    } else {
                        binding.itemEpisodeViewedCover.visibility = View.GONE
                        binding.itemEpisodeViewed.visibility = View.GONE
                    }
                } else {
                    binding.itemEpisodeViewedCover.visibility = View.GONE
                    binding.itemEpisodeViewed.visibility = View.GONE
                }

                handleProgress(
                    binding.itemMediaProgressCont,
                    binding.itemMediaProgress,
                    binding.itemMediaProgressEmpty,
                    media.id,
                    ep.number
                )
            }

            is EpisodeGridViewHolder -> {
                val binding = holder.binding
                setAnimation(fragment.requireContext(), holder.binding.root)

                val thumb = ep.thumb?.let {
                    if (it.url.isNotEmpty()) {
                        if (it.url.startsWith("content://") || it.url.startsWith("file://")) {
                            it.url
                        } else {
                            GlideUrl(it.url) { it.headers }
                        }
                    } else null
                }
                Glide.with(binding.itemMediaImage).load(thumb ?: media.cover).override(400, 0)
                    .into(binding.itemMediaImage)

                binding.itemEpisodeNumber.text = ep.number
                binding.itemEpisodeTitle.text = title

                if (ep.rating != null) {
                    binding.itemEpisodeRating.visibility = View.VISIBLE
                    binding.itemEpisodeRating.text = "★ ${ep.rating}"
                } else {
                    binding.itemEpisodeRating.visibility = View.GONE
                }

                if (ep.date != null) {
                    binding.itemEpisodeDate.visibility = View.VISIBLE
                    binding.itemEpisodeDate.text = ep.date
                } else {
                    binding.itemEpisodeDate.visibility = View.GONE
                }

                if (ep.filler) {
                    binding.itemEpisodeFiller.visibility = View.VISIBLE
                    binding.itemEpisodeFillerView.visibility = View.VISIBLE
                } else {
                    binding.itemEpisodeFiller.visibility = View.GONE
                    binding.itemEpisodeFillerView.visibility = View.GONE
                }
                val epNum = MediaNameAdapter.findEpisodeNumber(ep.number) ?: ep.number.toFloatOrNull() ?: 9999f
                if (media.userProgress != null) {
                    if (epNum <= media.userProgress!!.toFloat()) {
                        binding.itemEpisodeViewedCover.visibility = View.VISIBLE
                        binding.itemEpisodeViewed.visibility = View.VISIBLE
                    } else {
                        binding.itemEpisodeViewedCover.visibility = View.GONE
                        binding.itemEpisodeViewed.visibility = View.GONE
                    }
                } else {
                    binding.itemEpisodeViewedCover.visibility = View.GONE
                    binding.itemEpisodeViewed.visibility = View.GONE
                }
                handleProgress(
                    binding.itemMediaProgressCont,
                    binding.itemMediaProgress,
                    binding.itemMediaProgressEmpty,
                    media.id,
                    ep.number
                )
            }

            is EpisodeCompactViewHolder -> {
                val binding = holder.binding
                setAnimation(fragment.requireContext(), holder.binding.root)
                binding.itemEpisodeNumber.text = ep.number
                binding.itemEpisodeFillerView.isVisible = ep.filler
                val epNum = MediaNameAdapter.findEpisodeNumber(ep.number) ?: ep.number.toFloatOrNull() ?: 9999f
                if (media.userProgress != null) {
                    if (epNum <= media.userProgress!!.toFloat())
                        binding.itemEpisodeViewedCover.visibility = View.VISIBLE
                    else {
                        binding.itemEpisodeViewedCover.visibility = View.GONE
                    }
                }
                handleProgress(
                    binding.itemMediaProgressCont,
                    binding.itemMediaProgress,
                    binding.itemMediaProgressEmpty,
                    media.id,
                    ep.number
                )
            }
        }
        applySelection(holder, ep, false)
    }

    override fun getItemCount(): Int = arr.size
    private val downloadedEpisodes = mutableSetOf<String>()

    val currentType: Int get() = type

    private val boundKeys = HashMap<String, String>()

    private fun contentKey(ep: Episode): String =
        listOf(ep.title, ep.desc, ep.thumb?.url, ep.filler, ep.rating, ep.date).joinToString("\u0001")

    private fun isWatched(ep: Episode): Boolean {
        val progress = media.userProgress ?: return false
        val epNum = MediaNameAdapter.findEpisodeNumber(ep.number) ?: ep.number.toFloatOrNull() ?: 9999f
        return epNum <= progress.toFloat()
    }

    fun softUpdate(newArr: List<Episode>) {
        arr = newArr
        if (newArr.isEmpty()) return
        notifyItemRangeChanged(0, newArr.size, PAYLOAD_WATCHED)
        for (i in newArr.indices) {
            val ep = newArr[i]
            val bound = boundKeys[ep.number]
            if (bound != null && bound != contentKey(ep)) notifyItemChanged(i)
        }
    }

    fun refreshWatchState() {
        if (arr.isNotEmpty()) notifyItemRangeChanged(0, arr.size, PAYLOAD_WATCHED)
    }

    fun syncDownloaded(current: Map<String, Double>) {
        downloadedEpisodes.toList().filter { it !in current }.forEach { deleteDownload(it) }
        current.forEach { (number, size) ->
            if (number !in downloadedEpisodes) addToDownloadedEpisodes(number, size)
        }
    }

    /**
     * The alpha a view has in its layout (the dimming cover is only 33% opaque). Animations must
     * return to that value, never to 1, or the cover turns into a solid block hiding the tile.
     */
    private val baseAlpha = java.util.WeakHashMap<View, Float>()
    private fun base(v: View): Float = baseAlpha.getOrPut(v) { v.alpha }

    private class WatchViews(
        val cover: View,
        val badge: View?,
        val cont: LinearLayout,
        val bar: View,
        val empty: View,
        val anchor: View? = null
    )

    private fun watchViews(holder: RecyclerView.ViewHolder): WatchViews? = when (holder) {
        is EpisodeListViewHolder -> holder.binding.let {
            WatchViews(it.itemEpisodeViewedCover, it.itemEpisodeViewed, it.itemMediaProgressCont, it.itemMediaProgress, it.itemMediaProgressEmpty, it.itemMediaImage)
        }
        is EpisodeGridViewHolder -> holder.binding.let {
            WatchViews(it.itemEpisodeViewedCover, it.itemEpisodeViewed, it.itemMediaProgressCont, it.itemMediaProgress, it.itemMediaProgressEmpty, it.itemMediaImage)
        }
        is EpisodeCompactViewHolder -> holder.binding.let {
            WatchViews(it.itemEpisodeViewedCover, null, it.itemMediaProgressCont, it.itemMediaProgress, it.itemMediaProgressEmpty)
        }
        else -> null
    }

    private fun resetWatchAnim(holder: RecyclerView.ViewHolder) {
        val v = watchViews(holder) ?: return
        v.cover.animate().cancel()
        v.badge?.animate()?.cancel()
        EpisodeWatchAnimator.cancel(holder.itemView)
        holder.itemView.animate().cancel()
        v.cover.alpha = base(v.cover)
        v.badge?.let { it.alpha = base(it); it.scaleX = 1f; it.scaleY = 1f }
        holder.itemView.scaleX = 1f
        holder.itemView.scaleY = 1f
    }

    private fun bindWatchState(holder: RecyclerView.ViewHolder, ep: Episode) {
        val v = watchViews(holder) ?: return
        handleProgress(v.cont, v.bar, v.empty, media.id, ep.number)
        val watched = isWatched(ep)
        val shown = v.cover.visibility == View.VISIBLE
        if (watched == shown) return

        val card = holder.itemView as? ViewGroup ?: return
        val pop = android.view.animation.OvershootInterpolator(2.4f)
        v.cover.animate().cancel()
        v.badge?.animate()?.cancel()
        v.cover.alpha = base(v.cover)
        if (watched) {
            val stagger = EpisodeWatchAnimator.markWatched(card, v.cover, v.anchor)
            v.badge?.let {
                it.alpha = 0f
                it.scaleX = 0.2f
                it.scaleY = 0.2f
                it.visibility = View.VISIBLE
                it.animate().alpha(base(it)).scaleX(1f).scaleY(1f)
                    .setStartDelay(stagger + EpisodeWatchAnimator.BADGE_DELAY_MS).setDuration(520).setInterpolator(pop).start()
            }
        } else {
            EpisodeWatchAnimator.markUnwatched(card, v.cover)
            v.badge?.let { b ->
                b.animate().alpha(0f).scaleX(0.2f).scaleY(0.2f).setDuration(260).withEndAction {
                    b.visibility = View.GONE
                    b.alpha = base(b)
                    b.scaleX = 1f
                    b.scaleY = 1f
                }.start()
            }
        }
    }

    fun clearAllDownloaded() {
        downloadedEpisodes.clear()
    }

    fun startDownload(episodeNumber: String) {
        if (downloadedEpisodes.contains(episodeNumber) ||
            AnimeDownloader.isDownloading(media.id, episodeNumber))
                return
        AnimeDownloader.startDownload(media.id, episodeNumber)
        val position = arr.indexOfFirst { it.number == episodeNumber }
        if (position != -1) {
            arr[position].downloadProgress = ""
            notifyItemChanged(position, PAYLOAD_DOWNLOAD_STATE)
        }
    }

    @OptIn(UnstableApi::class)
    fun addToDownloadedEpisodes(episodeNumber: String, size: Double) {
        AnimeDownloader.stopDownload(media.id, episodeNumber)
        downloadedEpisodes.add(episodeNumber)
        val position = arr.indexOfFirst { it.number == episodeNumber }
        if (position != -1) {
            arr[position].downloadProgress = "Downloaded" + ": (${"%.1f".format(size)} MB)"
            notifyItemChanged(position, PAYLOAD_DOWNLOAD_STATE)
        }
    }

    fun deleteDownload(episodeNumber: String) {
        downloadedEpisodes.remove(episodeNumber)
        val position = arr.indexOfFirst { it.number == episodeNumber }
        if (position != -1) {
            arr[position].downloadProgress = null
            notifyItemChanged(position, PAYLOAD_DOWNLOAD_STATE)
        }
    }

    fun clearDownloadState(episodeNumber: String) {
        AnimeDownloader.stopDownload(media.id, episodeNumber)
        downloadedEpisodes.remove(episodeNumber)
        val position = arr.indexOfFirst { it.number == episodeNumber }
        if (position != -1) {
            arr[position].downloadProgress = null
            notifyItemChanged(position, PAYLOAD_DOWNLOAD_STATE)
        }
    }

    fun purgeDownload(episodeNumber: String) {
        AnimeDownloader.stopDownload(media.id, episodeNumber)
        downloadedEpisodes.remove(episodeNumber)
        val position = arr.indexOfFirst { it.number == episodeNumber }
        if (position != -1) {
            arr[position].downloadProgress = "Failed"
            notifyItemChanged(position, PAYLOAD_DOWNLOAD_STATE)
        }
    }

    fun updateDownloadProgress(episodeNumber: String, progress: Int) {
        updateDownloadProgress(episodeNumber, progress, -1L, -1L)
    }

    fun updateDownloadProgress(
        episodeNumber: String,
        progress: Int,
        downloadedBytes: Long,
        estimatedTotalBytes: Long
    ) {
        if (!AnimeDownloader.isDownloading(media.id, episodeNumber)) return
        val position = arr.indexOfFirst { it.number == episodeNumber }
        if (position == -1) return
        val text = buildDownloadProgressText(progress, downloadedBytes, estimatedTotalBytes)
        if (arr[position].downloadProgress == text) return
        arr[position].downloadProgress = text
        notifyItemChanged(position, PAYLOAD_PROGRESS)
    }

    private fun buildDownloadProgressText(
        progress: Int,
        downloadedBytes: Long,
        estimatedTotalBytes: Long
    ): String {
        val hasDownloaded = downloadedBytes > 0L
        val hasEstimatedTotal = estimatedTotalBytes > 0L
        return if (hasDownloaded && hasEstimatedTotal) {
            "Downloading: $progress% (${SizeFormatter.formatBytes(downloadedBytes)} / ${SizeFormatter.formatBytes(estimatedTotalBytes)} est.)"
        } else if (hasEstimatedTotal) {
            "Downloading: $progress% (~${SizeFormatter.formatBytes(estimatedTotalBytes)} est.)"
        } else {
            "Downloading: $progress%"
        }
    }


    inner class EpisodeCompactViewHolder(val binding: ItemEpisodeCompactBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos >= 0 && pos < arr.size) {
                    if (isSelecting()) toggleSelection(arr[pos].number)
                    else fragment.onEpisodeClick(arr[pos].number)
                }
            }
            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos >= 0 && pos < arr.size) toggleSelection(arr[pos].number)
                true
            }
        }
    }

    inner class EpisodeGridViewHolder(val binding: ItemEpisodeGridBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos >= 0 && pos < arr.size) {
                    if (isSelecting()) toggleSelection(arr[pos].number)
                    else fragment.onEpisodeClick(arr[pos].number)
                }
            }
            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos >= 0 && pos < arr.size) toggleSelection(arr[pos].number)
                true
            }
        }
    }

    inner class EpisodeListViewHolder(val binding: ItemEpisodeListBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private val activeCoroutines = mutableSetOf<String>()

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos >= 0 && pos < arr.size) {
                    if (isSelecting()) toggleSelection(arr[pos].number)
                    else fragment.onEpisodeClick(arr[pos].number)
                }
            }
            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos >= 0 && pos < arr.size) toggleSelection(arr[pos].number)
                true
            }
            binding.itemDownload.setOnClickListener {
                if (0 <= bindingAdapterPosition && bindingAdapterPosition < arr.size) {
                    val episodeNumber = arr[bindingAdapterPosition].number
                    if(AnimeDownloader.isDownloading(media.id, episodeNumber)){
                        fragment.onAnimeEpisodeStopDownloadClick(episodeNumber)
                        return@setOnClickListener
                    } else if (downloadedEpisodes.contains(episodeNumber)) {
                        binding.root.context.customAlertDialog().apply {
                            setTitle("Delete Episode")
                            setMessage("Are you sure you want to delete Episode $episodeNumber?")
                            setPosButton(R.string.yes) {
                                fragment.onAnimeEpisodeRemoveDownloadClick(episodeNumber)
                            }
                            setNegButton(R.string.no)
                        }.show()
                        return@setOnClickListener
                    } else {
                        fragment.onAnimeEpisodesDownload(arrayListOf(episodeNumber))
                    }
                }
            }
            binding.itemDownload.setOnLongClickListener {
                if (0 <= bindingAdapterPosition && bindingAdapterPosition < arr.size) {
                    val episodeNumber = arr[bindingAdapterPosition].number
                    if (downloadedEpisodes.contains(episodeNumber)) {
                        fragment.requireContext().customAlertDialog().apply {
                            setTitle("Multi Episode Deleter")
                            setMessage("Enter the number of episodes to delete")
                            val input = NumberPicker(currContext())
                            input.minValue = 1
                            input.maxValue = itemCount - bindingAdapterPosition
                            input.value = 1
                            setCustomView(input)
                            setPosButton(R.string.ok) {
                                binding.root.context.customAlertDialog().apply {
                                    setTitle("Delete Episodes")
                                    setMessage("Are you sure you want to delete Episodes $episodeNumber -> ${arr[bindingAdapterPosition + input.value - 1].number}?")
                                    setPosButton(R.string.yes) {
                                        fragment.multiDelete(episodeNumber, input.value)
                                    }
                                    setNegButton(R.string.no)
                                }.show()
                            }
                            setNegButton(R.string.cancel)
                            show()
                        }
                    }
                    else {
                        fragment.requireContext().customAlertDialog().apply {
                            setTitle("Multi Episode Downloader")
                            setMessage("Enter the number of episodes to download")
                            val input = NumberPicker(currContext())
                            input.minValue = 1
                            input.maxValue = itemCount - bindingAdapterPosition
                            input.value = 1
                            setCustomView(input)
                            setPosButton(R.string.ok) {
                                fragment.multiDownload(episodeNumber, input.value)
                            }
                            setNegButton(R.string.cancel)
                            show()
                        }
                    }
                }

                true
            }
            binding.itemEpisodeDesc.setOnClickListener {
                val pos = bindingAdapterPosition
                if (isSelecting() && pos >= 0 && pos < arr.size) {
                    toggleSelection(arr[pos].number)
                } else if (binding.itemEpisodeDesc.maxLines == 3) {
                    binding.itemEpisodeDesc.maxLines = 100
                } else {
                    binding.itemEpisodeDesc.maxLines = 3
                }
            }
            binding.itemEpisodeDesc.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos >= 0 && pos < arr.size) toggleSelection(arr[pos].number)
                true
            }
        }

        private fun stopDownloadAnimation() {
            binding.itemDownload.animate().cancel()
            binding.itemDownload.rotation = 0f
        }

        private fun updateDescSpacing() {
            val d = binding.itemEpisodeDesc
            val top = if (binding.itemDownloadStatus.isVisible) {
                (4 * d.resources.displayMetrics.density).toInt()
            } else {
                (16 * d.resources.displayMetrics.density).toInt()
            }
            if (d.paddingTop != top) d.setPadding(d.paddingLeft, top, d.paddingRight, d.paddingBottom)
        }

        fun bindProgressText(progress: String?) {
            if (progress.isNullOrEmpty()) {
                binding.itemDownloadStatus.visibility = View.GONE
                updateDescSpacing()
                return
            }
            binding.itemDownloadStatus.visibility = View.VISIBLE
            binding.itemDownloadStatus.text = progress
            updateDescSpacing()
        }

        fun bind(episodeNumber: String, progress: String?, desc: String?) {
            binding.itemEpisodeDesc.visibility =
                if (!desc.isNullOrBlank()) View.VISIBLE else View.GONE
            if (progress != null) {
                if (progress == "")
                    binding.itemDownloadStatus.visibility = View.GONE
                else
                    binding.itemDownloadStatus.visibility = View.VISIBLE
                binding.itemDownloadStatus.text = progress
            } else {
                binding.itemDownloadStatus.visibility = View.GONE
                binding.itemDownloadStatus.text = ""
            }
            if (media.format == "LOCAL") {
                binding.itemDownload.visibility = View.GONE
            } else {
                binding.itemDownload.visibility = View.VISIBLE
                if (AnimeDownloader.isDownloading(media.id, episodeNumber)) {
                    stopDownloadAnimation()
                    binding.itemDownload.setImageResource(R.drawable.ic_sync)
                    startOrContinueRotation(episodeNumber)
                } else if (downloadedEpisodes.contains(episodeNumber)) {
                    stopDownloadAnimation()
                    binding.itemDownloadStatus.visibility = View.VISIBLE

                    binding.itemDownload.setImageResource(R.drawable.ic_circle_check)
                    binding.itemDownload.postDelayed({
                        binding.itemDownload.setImageResource(R.drawable.ic_round_delete_24)
                        stopDownloadAnimation()
                    }, 1000)
                } else {
                    stopDownloadAnimation()
                    binding.itemDownloadStatus.visibility = View.GONE

                    binding.itemDownload.setImageResource(R.drawable.ic_download_24)
                }
            }
            updateDescSpacing()
        }

        private fun startOrContinueRotation(episodeNumber: String) {
            if (!isRotationCoroutineRunningFor(episodeNumber)) {
                val scope = fragment.lifecycle.coroutineScope
                scope.launch {
                    activeCoroutines.add(episodeNumber)
                    try {
                        while (AnimeDownloader.isDownloading(media.id, episodeNumber)) {
                            binding.itemDownload.animate()
                                .rotationBy(360f)
                                .setDuration(1000)
                                .setInterpolator(LinearInterpolator())
                                .start()
                            delay(1000)
                        }
                    } finally {
                        activeCoroutines.remove(episodeNumber)
                        stopDownloadAnimation()
                    }
                }
            }
        }

        private fun isRotationCoroutineRunningFor(episodeNumber: String): Boolean {
            return episodeNumber in activeCoroutines
        }
    }

    fun updateType(t: Int) {
        type = t
    }
}
