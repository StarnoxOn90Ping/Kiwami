package nox.kiwami.media.manga

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.animation.LinearInterpolator
import android.widget.NumberPicker
import androidx.lifecycle.coroutineScope
import androidx.recyclerview.widget.RecyclerView
import nox.kiwami.R
import nox.kiwami.connections.updateProgress
import nox.kiwami.currContext
import nox.kiwami.databinding.ItemChapterListBinding
import nox.kiwami.databinding.ItemEpisodeCompactBinding
import nox.kiwami.media.Media
import nox.kiwami.media.MediaNameAdapter
import nox.kiwami.media.anime.EpisodeWatchAnimator
import nox.kiwami.media.anime.applySelectionStyle
import nox.kiwami.setAnimation
import nox.kiwami.util.SizeFormatter
import nox.kiwami.util.customAlertDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MangaChapterAdapter(
    private var type: Int,
    private val media: Media,
    private val fragment: MangaReadFragment,
    var arr: ArrayList<MangaChapter> = arrayListOf(),
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val PAYLOAD_SELECTION = "selection"

        private const val PAYLOAD_READ = "read_state"
        private const val PAYLOAD_PROGRESS = "download_progress"
        private const val PAYLOAD_DOWNLOAD_STATE = "download_state"
    }

    val selectedChapters = LinkedHashSet<String>()
    var onSelectionChanged: ((Int) -> Unit)? = null

    fun isSelecting(): Boolean = selectedChapters.isNotEmpty()

    fun toggleSelection(chapter: MangaChapter) {
        val key = chapter.uniqueNumber()
        if (!selectedChapters.remove(key)) selectedChapters.add(key)
        val index = arr.indexOfFirst { it.uniqueNumber() == key }
        if (index >= 0) notifyItemChanged(index, PAYLOAD_SELECTION)
        onSelectionChanged?.invoke(selectedChapters.size)
    }

    fun selectAll() {
        arr.forEach { selectedChapters.add(it.uniqueNumber()) }
        notifyItemRangeChanged(0, arr.size, PAYLOAD_SELECTION)
        onSelectionChanged?.invoke(selectedChapters.size)
    }

    fun clearSelection() {
        if (selectedChapters.isEmpty()) return
        selectedChapters.clear()
        notifyItemRangeChanged(0, arr.size, PAYLOAD_SELECTION)
        onSelectionChanged?.invoke(0)
    }

    private fun applySelection(holder: RecyclerView.ViewHolder, chapter: MangaChapter, animate: Boolean) {
        val card: android.widget.FrameLayout = when (holder) {
            is ChapterCompactViewHolder -> holder.binding.itemEpisodeCont
            is ChapterListViewHolder -> holder.binding.root
            else -> return
        }
        applySelectionStyle(card, selectedChapters.contains(chapter.uniqueNumber()), animate)
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        var handled = false
        if (payloads.contains(PAYLOAD_SELECTION)) {
            if (position in arr.indices) applySelection(holder, arr[position], true)
            handled = true
        }
        if (payloads.contains(PAYLOAD_READ)) {
            if (position in arr.indices) bindReadState(holder, arr[position])
            handled = true
        }
        val listHolder = holder as? ChapterListViewHolder
        if (payloads.contains(PAYLOAD_DOWNLOAD_STATE)) {
            if (position in arr.indices) listHolder?.bindDownloadState(arr[position])
            handled = true
        }
        if (payloads.contains(PAYLOAD_PROGRESS)) {
            if (position in arr.indices) listHolder?.bindProgressText(arr[position].progress)
            handled = true
        }
        if (handled) return
        super.onBindViewHolder(holder, position, payloads)
    }

    fun refreshReadState() {
        if (arr.isNotEmpty()) notifyItemRangeChanged(0, arr.size, PAYLOAD_READ)
    }

    val currentType: Int get() = type

    private val boundKeys = HashMap<String, String>()

    private val baseAlpha = java.util.WeakHashMap<View, Float>()
    private fun base(v: View): Float = baseAlpha.getOrPut(v) { v.alpha }

    private fun contentKey(chapter: MangaChapter): String =
        listOf(chapter.title, chapter.description, chapter.date, chapter.scanlator, chapter.link)
            .joinToString("\u0001")

    fun softUpdate(newArr: ArrayList<MangaChapter>) {
        arr = newArr
        if (newArr.isEmpty()) return
        notifyItemRangeChanged(0, newArr.size, PAYLOAD_READ)
        for (i in newArr.indices) {
            val chapter = newArr[i]
            if (chapter.progress.isNullOrEmpty() && isDownloaded(chapter)) {
                chapter.progress = downloadedText(chapter)
                notifyItemChanged(i, PAYLOAD_PROGRESS)
            }
            val bound = boundKeys[chapter.uniqueNumber()]
            if (bound != null && bound != contentKey(chapter)) notifyItemChanged(i)
        }
    }

    fun markDownloaded(name: String) {
        if (downloadedChapters.contains(name)) return
        stopDownload(name)
    }

    private class ReadViews(val cover: View, val badge: View?)

    private fun resetReadAnim(holder: RecyclerView.ViewHolder) {
        val v = readViews(holder) ?: return
        v.cover.animate().cancel()
        v.badge?.animate()?.cancel()
        EpisodeWatchAnimator.cancel(holder.itemView)
        holder.itemView.animate().cancel()
        v.cover.alpha = base(v.cover)
        v.badge?.let {
            it.alpha = base(it)
            it.scaleX = 1f
            it.scaleY = 1f
        }
        holder.itemView.scaleX = 1f
        holder.itemView.scaleY = 1f
    }

    private fun readViews(holder: RecyclerView.ViewHolder): ReadViews? = when (holder) {
        is ChapterCompactViewHolder -> ReadViews(holder.binding.itemEpisodeViewedCover, null)
        is ChapterListViewHolder ->
            ReadViews(holder.binding.itemEpisodeViewedCover, holder.binding.itemEpisodeViewed)
        else -> null
    }

    private fun isRead(chapter: MangaChapter): Boolean {
        val progress = media.userProgress ?: return false
        return (MediaNameAdapter.findChapterNumber(chapter.number) ?: 9999f) <= progress.toFloat()
    }

    private fun showReadState(holder: RecyclerView.ViewHolder, chapter: MangaChapter) {
        val views = readViews(holder) ?: return
        EpisodeWatchAnimator.cancel(holder.itemView)
        views.badge?.animate()?.cancel()
        val read = isRead(chapter)
        views.cover.visibility = if (read) View.VISIBLE else View.GONE
        views.badge?.let {
            it.visibility = if (read) View.VISIBLE else View.GONE
            it.alpha = base(it)
            it.scaleX = 1f
            it.scaleY = 1f
        }
    }

    private fun bindReadState(holder: RecyclerView.ViewHolder, chapter: MangaChapter) {
        val views = readViews(holder) ?: return
        val card = holder.itemView as? ViewGroup ?: return
        val read = isRead(chapter)
        if (read == (views.cover.visibility == View.VISIBLE)) return

        views.cover.animate().cancel()
        views.badge?.animate()?.cancel()
        views.cover.alpha = base(views.cover)
        if (read) {
            val stagger = EpisodeWatchAnimator.markWatched(card, views.cover, null)
            views.badge?.let {
                it.alpha = 0f
                it.scaleX = 0.2f
                it.scaleY = 0.2f
                it.visibility = View.VISIBLE
                it.animate().alpha(base(it)).scaleX(1f).scaleY(1f)
                    .setStartDelay(stagger + EpisodeWatchAnimator.BADGE_DELAY_MS).setDuration(520)
                    .setInterpolator(OvershootInterpolator(2.4f)).start()
            }
        } else {
            EpisodeWatchAnimator.markUnwatched(card, views.cover)
            views.badge?.let { badge ->
                badge.animate().alpha(0f).scaleX(0.2f).scaleY(0.2f).setDuration(260).withEndAction {
                    badge.visibility = View.GONE
                    badge.alpha = base(badge)
                    badge.scaleX = 1f
                    badge.scaleY = 1f
                }.start()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            1 -> ChapterCompactViewHolder(
                ItemEpisodeCompactBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            0 -> ChapterListViewHolder(
                ItemChapterListBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            else -> throw IllegalArgumentException()
        }
    }

    override fun getItemViewType(position: Int): Int {
        return type
    }

    override fun getItemCount(): Int = arr.size

    inner class ChapterCompactViewHolder(val binding: ItemEpisodeCompactBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos >= 0 && pos < arr.size) {
                    if (isSelecting()) toggleSelection(arr[pos])
                    else fragment.onMangaChapterClick(arr[pos])
                }
            }
            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos >= 0 && pos < arr.size) toggleSelection(arr[pos])
                true
            }
        }
    }

    private val activeDownloads = mutableSetOf<String>()
    private val downloadedChapters = mutableSetOf<String>()
    private val downloadedSizes = HashMap<String, String>()

    private fun downloadedText(chapter: MangaChapter): String {
        val size = downloadedSizes[chapter.uniqueNumber()]
        return if (size.isNullOrEmpty()) "Downloaded" else "Downloaded: ($size)"
    }

    fun hasDownloadedSize(name: String): Boolean {
        val chapter = arr.firstOrNull { it.uniqueNumber() == name || it.number == name }
            ?: return true
        return downloadedSizes.containsKey(chapter.uniqueNumber())
    }

    fun setDownloadedSize(name: String, bytes: Long) {
        val position = arr.indexOfFirst { it.uniqueNumber() == name || it.number == name }
        if (position == -1) return
        val chapter = arr[position]
        downloadedSizes[chapter.uniqueNumber()] =
            if (bytes > 0L) SizeFormatter.formatBytes(bytes) else ""
        if (bytes <= 0L || !isDownloaded(chapter) || isDownloading(chapter.uniqueNumber())) return
        val text = downloadedText(chapter)
        if (chapter.progress == text) return
        chapter.progress = text
        notifyItemChanged(position, PAYLOAD_PROGRESS)
    }

    fun isDownloading(chapterNumber: String): Boolean = activeDownloads.contains(chapterNumber)
    fun isDownloaded(chapterNumber: String): Boolean = downloadedChapters.contains(chapterNumber)

    fun isDownloaded(chapter: MangaChapter): Boolean {
        val unique = chapter.uniqueNumber()
        val num = chapter.number
        val title = chapter.title
        val titleUnique = title?.let { "$it-${chapter.scanlator ?: "Unknown"}" }
        return downloadedChapters.contains(unique) ||
                downloadedChapters.contains(num) ||
                (title != null && downloadedChapters.contains(title)) ||
                (titleUnique != null && downloadedChapters.contains(titleUnique))
    }

    fun startDownload(chapterNumber: String) {
        activeDownloads.add(chapterNumber)
        val position = arr.indexOfFirst { it.uniqueNumber() == chapterNumber || it.number == chapterNumber }
        if (position != -1) {
            val chapter = arr[position]
            activeDownloads.add(chapter.uniqueNumber())
            activeDownloads.add(chapter.number)
            notifyItemChanged(position, PAYLOAD_DOWNLOAD_STATE)
        }
    }

    fun stopDownload(chapterNumber: String) {
        activeDownloads.remove(chapterNumber)
        downloadedChapters.add(chapterNumber)
        val position = arr.indexOfFirst { it.uniqueNumber() == chapterNumber || it.number == chapterNumber }
        if (position != -1) {
            val chapter = arr[position]
            activeDownloads.remove(chapter.uniqueNumber())
            activeDownloads.remove(chapter.number)
            downloadedChapters.add(chapter.uniqueNumber())
            downloadedChapters.add(chapter.number)
            chapter.progress = downloadedText(chapter)
            notifyItemChanged(position, PAYLOAD_DOWNLOAD_STATE)
        }
    }

    fun deleteDownload(chapterNumber: MangaChapter) {
        downloadedChapters.remove(chapterNumber.uniqueNumber())
        downloadedChapters.remove(chapterNumber.number)
        activeDownloads.remove(chapterNumber.uniqueNumber())
        activeDownloads.remove(chapterNumber.number)
        chapterNumber.title?.let { downloadedChapters.remove(it) }
        downloadedSizes.remove(chapterNumber.uniqueNumber())
        val position = arr.indexOfFirst { it.uniqueNumber() == chapterNumber.uniqueNumber() }
        if (position != -1) {
            arr[position].progress = ""
            notifyItemChanged(position, PAYLOAD_DOWNLOAD_STATE)
        }
    }

    fun purgeDownload(chapterNumber: String) {
        activeDownloads.remove(chapterNumber)
        downloadedChapters.remove(chapterNumber)
        val position = arr.indexOfFirst { it.uniqueNumber() == chapterNumber || it.number == chapterNumber }
        if (position != -1) {
            val chapter = arr[position]
            activeDownloads.remove(chapter.uniqueNumber())
            activeDownloads.remove(chapter.number)
            downloadedChapters.remove(chapter.uniqueNumber())
            downloadedChapters.remove(chapter.number)
            downloadedSizes.remove(chapter.uniqueNumber())
            chapter.progress = ""
            notifyItemChanged(position, PAYLOAD_DOWNLOAD_STATE)
        }
    }

    fun updateDownloadProgress(chapterNumber: String, progress: Int) {
        updateDownloadProgress(chapterNumber, progress, -1L, -1L)
    }

    fun updateDownloadProgress(
        chapterNumber: String,
        progress: Int,
        downloadedBytes: Long,
        estimatedTotalBytes: Long
    ) {
        val position = arr.indexOfFirst { it.uniqueNumber() == chapterNumber }
        if (position == -1) return
        val text = buildDownloadProgressText(progress, downloadedBytes, estimatedTotalBytes)
        if (arr[position].progress == text) return
        arr[position].progress = text
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

    fun downloadNChaptersFrom(position: Int, n: Int) {
        if (position < 0 || position >= arr.size) return
        for (i in 0..<n) {
            if (position + i < arr.size) {
                val chapter = arr[position + i]
                val chapterNumber = chapter.uniqueNumber()
                if (activeDownloads.contains(chapterNumber)) {
                    continue
                } else if (isDownloaded(chapter)) {
                    continue
                } else {
                    fragment.onMangaChapterDownloadClick(chapter)
                }
            }
        }
    }

    fun deleteNChaptersFrom(position: Int, n: Int){
        if (position < 0 || position >= arr.size) return
        for (i in 0..<n) {
            if (position + i < arr.size) {
                val chapter = arr[position + i]
                val chapterNumber = chapter.uniqueNumber()
                if(activeDownloads.contains(chapterNumber) || activeDownloads.contains(chapter.number)){
                    fragment.onMangaChapterStopDownloadClick(chapter)
                }
                else if (isDownloaded(chapter)) {
                    fragment.onMangaChapterRemoveDownloadClick(chapter)
                }
            }
        }
    }

    inner class ChapterListViewHolder(val binding: ItemChapterListBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private val activeCoroutines = mutableSetOf<String>()
        fun bindProgressText(progress: String?) {
            if (progress.isNullOrEmpty()) {
                binding.itemChapterTitle.visibility = View.GONE
                binding.itemChapterTitle.text = ""
            } else {
                binding.itemChapterTitle.visibility = View.VISIBLE
                binding.itemChapterTitle.text = progress
            }
        }

        fun bindDownloadState(chapter: MangaChapter) {
            bind(chapter, chapter.progress)
            bindProgressText(chapter.progress)
        }

        fun bind(chapter: MangaChapter, progress: String?) {
            val chapterNumber = chapter.uniqueNumber()
            if (progress != null) {
                binding.itemChapterTitle.visibility = View.VISIBLE
                binding.itemChapterTitle.text = "$progress"
            } else {
                binding.itemChapterTitle.visibility = View.GONE
                binding.itemChapterTitle.text = ""
            }

            if (media.format == "LOCAL") {
                binding.itemDownload.visibility = View.GONE
                return
            }
            binding.itemDownload.visibility = View.VISIBLE

            if (activeDownloads.contains(chapterNumber) || activeDownloads.contains(chapter.number)) {
                binding.itemDownload.setImageResource(R.drawable.ic_sync)
                startOrContinueRotation(chapter) {
                    binding.itemDownload.rotation = 0f
                }
            } else if (isDownloaded(chapter)) {
                binding.itemDownload.setImageResource(R.drawable.ic_round_delete_24)
                binding.itemDownload.rotation = 0f
            } else {
                binding.itemDownload.setImageResource(R.drawable.ic_download_24)
                binding.itemDownload.rotation = 0f
            }

        }

        private fun startOrContinueRotation(chapter: MangaChapter, resetRotation: () -> Unit) {
            val key = chapter.uniqueNumber()
            if (!isRotationCoroutineRunningFor(key)) {
                val scope = fragment.lifecycle.coroutineScope
                scope.launch {
                    activeCoroutines.add(key)
                    while (activeDownloads.contains(chapter.uniqueNumber()) || activeDownloads.contains(chapter.number)) {
                        binding.itemDownload.animate().rotationBy(360f).setDuration(1000)
                            .setInterpolator(
                                LinearInterpolator()
                            ).start()
                        delay(1000)
                    }
                    activeCoroutines.remove(key)
                    resetRotation()
                }
            }
        }

        private fun isRotationCoroutineRunningFor(chapterNumber: String): Boolean {
            return chapterNumber in activeCoroutines
        }

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos >= 0 && pos < arr.size) {
                    if (isSelecting()) toggleSelection(arr[pos])
                    else fragment.onMangaChapterClick(arr[pos])
                }
            }
            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos >= 0 && pos < arr.size) toggleSelection(arr[pos])
                true
            }
            binding.itemDownload.setOnClickListener {
                if (0 <= bindingAdapterPosition && bindingAdapterPosition < arr.size) {
                    val chapter = arr[bindingAdapterPosition]
                    val chapterNumber = chapter.uniqueNumber()
                    if (activeDownloads.contains(chapterNumber) || activeDownloads.contains(chapter.number)) {
                        fragment.onMangaChapterStopDownloadClick(chapter)
                        return@setOnClickListener
                    } else if (isDownloaded(chapter)) {
                        it.context.customAlertDialog().apply {
                            setTitle("Delete Chapter")
                            setMessage("Are you sure you want to delete ${chapter.number}?")
                            setPosButton(R.string.delete) {
                                fragment.onMangaChapterRemoveDownloadClick(chapter)
                            }
                            setNegButton(R.string.cancel)
                            show()
                        }
                        return@setOnClickListener
                    } else {
                        fragment.onMangaChapterDownloadClick(chapter)
                    }
                }
            }
            binding.itemDownload.setOnLongClickListener {
                if (0 <= bindingAdapterPosition && bindingAdapterPosition < arr.size){
                    val chapter = arr[bindingAdapterPosition]
                    val chapterNumber = chapter.uniqueNumber()
                    if(activeDownloads.contains(chapterNumber) || isDownloaded(chapter)){
                        fragment.requireContext().customAlertDialog().apply {
                            setTitle("Multi Chapter Deleter")
                            setMessage("Enter the number of chapters to delete")
                            val input = NumberPicker(currContext())
                            input.minValue = 1
                            input.maxValue = itemCount - bindingAdapterPosition
                            input.value = 1
                            setCustomView(input)
                            setPosButton(R.string.ok) {
                                binding.root.context.customAlertDialog().apply {
                                    setTitle("Delete Chapters")
                                    setMessage("Are you sure you want to delete the next ${input.value} chapters?")
                                    setPosButton(R.string.yes) {
                                        deleteNChaptersFrom(bindingAdapterPosition, input.value)
                                    }
                                    setNegButton(R.string.no)
                                }.show()
                            }
                            setNegButton(R.string.cancel)
                            show()
                        }
                    }
                    else{
                        it.context.customAlertDialog().apply {
                            setTitle("Multi Chapter Downloader")
                            setMessage("Enter the number of chapters to download")
                            val input = NumberPicker(currContext())
                            input.minValue = 1
                            input.maxValue = itemCount - bindingAdapterPosition
                            input.value = 1
                            setCustomView(input)
                            setPosButton("OK") {
                                downloadNChaptersFrom(bindingAdapterPosition, input.value)
                            }
                            setNegButton("Cancel")
                            show()
                        }
                    }
                }
                true
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        resetReadAnim(holder)
        if (position in arr.indices) boundKeys[arr[position].uniqueNumber()] = contentKey(arr[position])
        when (holder) {
            is ChapterCompactViewHolder -> {
                val binding = holder.binding
                setAnimation(fragment.requireContext(), holder.binding.root)
                val ep = arr[position]
                val parsedNumber = MediaNameAdapter.findChapterNumber(ep.number)?.toInt()
                binding.itemEpisodeNumber.text = parsedNumber?.toString() ?: ep.number
                showReadState(holder, ep)
            }

            is ChapterListViewHolder -> {
                val binding = holder.binding
                val ep = arr[position]
                holder.bind(ep, ep.progress)
                setAnimation(fragment.requireContext(), holder.binding.root)
                binding.itemChapterNumber.text = ep.number

                if (ep.date != null) {
                    binding.itemChapterDateLayout.visibility = View.VISIBLE
                    binding.itemChapterDate.text = formatDate(ep.date)
                }
                if (ep.scanlator != null) {
                    binding.itemChapterDateLayout.visibility = View.VISIBLE
                    binding.itemChapterScan.text = ep.scanlator.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(
                            Locale.ROOT
                        ) else it.toString()
                    }
                }
                if (formatDate(ep.date) == "" || ep.scanlator == null) {
                    binding.itemChapterDateDivider.visibility = View.GONE
                } else binding.itemChapterDateDivider.visibility = View.VISIBLE

                if (ep.progress.isNullOrEmpty()) {
                    binding.itemChapterTitle.visibility = View.GONE
                } else binding.itemChapterTitle.visibility = View.VISIBLE

                showReadState(holder, ep)
            }
        }
        if (position in arr.indices) applySelection(holder, arr[position], false)
    }

    fun updateType(t: Int) {
        type = t
    }

    fun formatDate(timestamp: Long?): String {
        timestamp ?: return ""

        val targetDate = Date(timestamp)

        if (targetDate < Date(946684800000L)) {
            return ""
        }

        val currentDate = Date()
        val difference = currentDate.time - targetDate.time

        return when (val daysDifference = difference / (1000 * 60 * 60 * 24)) {
            0L -> {
                val hoursDifference = difference / (1000 * 60 * 60)
                val minutesDifference = (difference / (1000 * 60)) % 60

                when {
                    hoursDifference > 0 -> "$hoursDifference hour${if (hoursDifference > 1) "s" else ""} ago"
                    minutesDifference > 0 -> "$minutesDifference minute${if (minutesDifference > 1) "s" else ""} ago"
                    else -> "Just now"
                }
            }

            1L -> "1 day ago"
            in 2..6 -> "$daysDifference days ago"
            else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(targetDate)
        }
    }

}
