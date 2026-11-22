package nox.kiwami.media.manga

import android.content.Intent
import android.util.Log
import android.view.LayoutInflater
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import androidx.core.content.ContextCompat.getString
import androidx.core.content.ContextCompat.startActivity
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import nox.kiwami.R
import nox.kiwami.currActivity
import nox.kiwami.currContext
import nox.kiwami.databinding.CustomDialogLayoutBinding
import nox.kiwami.databinding.DialogLayoutBinding
import nox.kiwami.databinding.ItemMediaSourceBinding
import nox.kiwami.isOnline
import nox.kiwami.loadImage
import nox.kiwami.media.Media
import nox.kiwami.media.MediaDetailsActivity
import nox.kiwami.media.MediaNameAdapter
import nox.kiwami.media.SourceCardController
import nox.kiwami.util.CountLabelController
import nox.kiwami.media.SourcePicker
import nox.kiwami.media.SourceSearchDialogFragment
import nox.kiwami.media.anime.handleProgress
import nox.kiwami.openSettings
import nox.kiwami.others.LanguageMapper
import nox.kiwami.others.webview.CookieCatcher
import nox.kiwami.parsers.DynamicMangaParser
import nox.kiwami.parsers.MangaReadSources
import nox.kiwami.parsers.MangaSources
import nox.kiwami.parsers.OfflineMangaParser
import nox.kiwami.px
import nox.kiwami.settings.FAQActivity
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import nox.kiwami.snackString
import nox.kiwami.toast
import nox.kiwami.util.customAlertDialog
import eu.kanade.tachiyomi.data.notification.Notifications.CHANNEL_SUBSCRIPTION_CHECK
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.WebViewUtil
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch


class MangaReadAdapter(
    private val media: Media,
    private val fragment: MangaReadFragment,
    private val mangaReadSources: MangaReadSources
) : RecyclerView.Adapter<MangaReadAdapter.ViewHolder>() {

    private var _binding: ItemMediaSourceBinding? = null

    private var sourceCard: SourceCardController? = null
    var onSourceSnapshot: ((SourceCardController.Snapshot) -> Unit)? = null
    private var countLabel: CountLabelController? = null

    fun clearBinding() {
        sourceCard?.release()
        sourceCard = null
        countLabel = null
        _binding = null
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        sourceCard?.release()
        sourceCard = null
        countLabel = null
        _binding = null
    }
    val hiddenScanlators = mutableListOf<String>()
    var scanlatorSelectionListener: ScanlatorSelectionListener? = null
    var options = listOf<String>()
        set(value) {
            field = value
            updateScanlatorDropdown()
        }

    private fun clearCustomValsForMedia(mediaId: String, suffix: String) {
        val customVals = PrefManager.getAllCustomValsForMedia("$mediaId$suffix")
        customVals.forEach { (key) ->
            PrefManager.removeCustomVal(key)
            Log.d("PrefManager", "Removed key: $key")
        }
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val bind =
            ItemMediaSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(bind)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val binding = holder.binding
        _binding = binding
        binding.sourceTitle.setText(R.string.chaps)

        binding.faqbutton.setOnClickListener {
            val intent = Intent(fragment.requireContext(), FAQActivity::class.java)
            startActivity(fragment.requireContext(), intent, null)
        }

        binding.mediaSourceSearch.setOnClickListener {
            SourceSearchDialogFragment().show(
                fragment.requireActivity().supportFragmentManager,
                null
            )
        }
        binding.sourceTitle.setOnLongClickListener {
            fragment.requireContext().customAlertDialog().apply {
                setTitle(" Delete Progress for all chapters of ${media.nameRomaji}")
                setMessage("This will delete all the locally stored progress for chapters")
                setPosButton(R.string.ok) {
                    clearCustomValsForMedia("${media.id}", "_Chapter")
                    clearCustomValsForMedia("${media.id}", "_Vol")
                    snackString("Deleted the progress of Chapters for ${media.nameRomaji}")
                }
                setNegButton(R.string.no)
                show()
            }
            true
        }

        var source =
            media.selected!!.sourceIndex.let { if (it >= mangaReadSources.names.size) 0 else it }
        setLanguageList(media.selected!!.langIndex, source)
        updateScanlatorDropdown()
        if (mangaReadSources.names.isNotEmpty() && source in 0 until mangaReadSources.names.size) {
            binding.mediaSource.setText(mangaReadSources.names[source])
            mangaReadSources[source].apply {
                binding.mediaSourceTitle.text = showUserText
                showUserTextListener = { MainScope().launch { binding.mediaSourceTitle.text = it } }
            }
        }
        hiddenScanlators.clear()
        media.selected?.scanlators?.let {
            hiddenScanlators.addAll(it)
        }
        val displayNames = mangaReadSources.names.filter { it != "Local" }
        binding.mediaSource.setAdapter(
            ArrayAdapter(
                fragment.requireContext(),
                R.layout.item_dropdown,
                displayNames
            )
        )
        binding.mediaSourceTitle.isSelected = true
        sourceCard?.release()
        sourceCard = SourceCardController(
            card = binding.sourceCard,
            nameView = binding.sourceCardName,
            indicator = binding.sourceStatusIndicator,
            statusText = binding.sourceStatusText,
            chevron = binding.sourceChevron,
            pill = binding.mediaSourceSearch,
            pillText = binding.mediaSourceSearchText,
            pillIcon = binding.mediaSourceSearchIcon,
            gear = binding.mediaSourceSettings,
            nameSource = binding.mediaSource,
            titleSource = binding.mediaSourceTitle,
            unit = "ch",
            onOpen = {
                binding.mediaSource.performClick()
                SourcePicker.show(
                    context = fragment.requireContext(),
                    items = displayNames.map { name ->
                        val index = mangaReadSources.names.indexOf(name)
                        val parser = if (index >= 0) mangaReadSources[index] else null
                        val extension = (parser as? DynamicMangaParser)?.extension
                        SourcePicker.Item(
                            name,
                            SourcePicker.subtitle(
                                extension?.lang,
                                extension?.versionName,
                                extension?.isNsfw == true
                            )
                        )
                    },
                    selected = binding.mediaSource.text.toString(),
                    recentsKey = "manga",
                    onPick = { index ->
                        binding.mediaSource.setText(displayNames[index], false)
                        binding.mediaSource.onItemClickListener
                            ?.onItemClick(null, null, index, index.toLong())
                    },
                    onDismiss = { sourceCard?.closed() }
                )
            }
        ).also {
            it.onSnapshot = { snapshot -> onSourceSnapshot?.invoke(snapshot) }
            it.bind()
        }
        countLabel = CountLabelController(binding.sourceCountText)
        binding.mediaSource.setOnItemClickListener { _, _, i, _ ->
            val actualIndex = mangaReadSources.names.indexOf(displayNames[i])
            fragment.onSourceChange(actualIndex, true).apply {
                binding.mediaSourceTitle.text = showUserText
                showUserTextListener = { MainScope().launch { binding.mediaSourceTitle.text = it } }
                source = actualIndex
                setLanguageList(0, actualIndex)
            }
            val invalidate = actualIndex == mangaReadSources.names.size - 1
            fragment.loadChapters(actualIndex, invalidate)
        }

        binding.mediaSourceLanguage.setOnItemClickListener { _, _, i, _ ->
            (mangaReadSources[source] as? DynamicMangaParser)?.let { ext ->
                ext.sourceLanguage = i
                fragment.onLangChange(i, ext.saveName)
                fragment.onSourceChange(media.selected!!.sourceIndex).apply {
                    binding.mediaSourceTitle.text = showUserText
                    showUserTextListener =
                        { MainScope().launch { binding.mediaSourceTitle.text = it } }
                    setLanguageList(i, source)
                }
                fragment.loadChapters(media.selected!!.sourceIndex, true)
            } ?: run {
            }
        }

        binding.mediaSourceSettings.setOnClickListener {
            (mangaReadSources[source] as? DynamicMangaParser)?.let { ext ->
                fragment.openSettings(ext.extension)
            }
        }

        binding.mediaTorrentButton.visibility = View.GONE

        binding.mediaNestedButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            val dialogBinding = DialogLayoutBinding.inflate(fragment.layoutInflater)
            var refresh = false
            var run = false
            var reversed = media.selected!!.recyclerReversed
            var style =
                media.selected!!.recyclerStyle ?: PrefManager.getVal(PrefName.MangaDefaultView)
            dialogBinding.apply {
                mediaSourceTop.rotation = if (reversed) -90f else 90f
                sortText.text = if (reversed) "Down to Up" else "Up to Down"
                mediaSourceTop.setOnClickListener {
                    reversed = !reversed
                    mediaSourceTop.rotation = if (reversed) -90f else 90f
                    sortText.text = if (reversed) "Down to Up" else "Up to Down"
                    run = true
                }

                mediaSourceGrid.visibility = View.GONE
                var selected = when (style) {
                    0 -> mediaSourceList
                    1 -> mediaSourceCompact
                    else -> mediaSourceList
                }
                when (style) {
                    0 -> layoutText.setText(R.string.list)
                    1 -> layoutText.setText(R.string.compact)
                    else -> mediaSourceList
                }
                selected.alpha = 1f
                fun selected(it: ImageButton) {
                    selected.alpha = 0.33f
                    selected = it
                    selected.alpha = 1f
                }
                mediaSourceList.setOnClickListener {
                    selected(it as ImageButton)
                    style = 0
                    layoutText.setText(R.string.list)
                    run = true
                }
                mediaSourceCompact.setOnClickListener {
                    selected(it as ImageButton)
                    style = 1
                    layoutText.setText(R.string.compact)
                    run = true
                }
                mediaWebviewContainer.setOnClickListener {
                    if (!WebViewUtil.supportsWebView(fragment.requireContext())) {
                        toast(R.string.webview_not_installed)
                    }
                    if (mangaReadSources.names.isNotEmpty() && source in 0 until mangaReadSources.names.size) {
                        val sourceAHH = mangaReadSources[source] as? DynamicMangaParser
                        val sourceHttp = sourceAHH?.extension?.sources?.firstOrNull() as? HttpSource
                        val url = sourceHttp?.baseUrl
                        url?.let {
                            refresh = true
                            val headersMap = try {
                                sourceHttp.headers.toMultimap()
                                    .mapValues { it.value.getOrNull(0) ?: "" }
                            } catch (e: Exception) {
                                emptyMap()
                            }
                            val intent =
                                Intent(fragment.requireContext(), CookieCatcher::class.java)
                                    .putExtra("url", url)
                                    .putExtra("headers", headersMap as HashMap<String, String>)
                            startActivity(fragment.requireContext(), intent, null)
                        }
                    }
                }

                if (media.format == "LOCAL") {
                    animeDownloadContainer.visibility = View.GONE
                    mediaWebviewContainer.visibility = View.GONE
                } else {
                    mediaDownloadTop.visibility = View.VISIBLE
                    mediaDownloadTop.setOnClickListener {
                        fragment.requireContext().customAlertDialog().apply {
                            setTitle("Multi Chapter Downloader")
                        setMessage("Enter the number of chapters to download")
                        val input = View.inflate(currContext(), R.layout.dialog_layout, null)
                        val editText = input.findViewById<EditText>(R.id.downloadNo)
                        setCustomView(input)
                        setPosButton(R.string.ok) {
                            val value = editText.text.toString().toIntOrNull()
                            if (value != null && value > 0) {
                                downloadNo.setText(value.toString(), TextView.BufferType.EDITABLE)
                                fragment.multiDownload(value)
                            } else {
                                toast("Please enter a valid number")
                            }
                        }
                        setNegButton(R.string.cancel)
                        show()
                    }
                }
                }
                resetProgress.setOnClickListener {
                    fragment.requireContext().customAlertDialog().apply {
                        setTitle(" Delete Progress for all chapters of ${media.nameRomaji}")
                        setMessage("This will delete all the locally stored progress for chapters")
                        setPosButton(R.string.ok) {
                            clearCustomValsForMedia("${media.id}", "_Chapter")
                            clearCustomValsForMedia("${media.id}", "_Vol")

                            snackString("Deleted the progress of Chapters for ${media.nameRomaji}")
                        }
                        setNegButton(R.string.no)
                        show()
                    }
                }
                resetProgressDef.text = getString(currContext()!!, R.string.clear_stored_chapter)

                fragment.requireContext().customAlertDialog().apply {
                    setTitle("Options")
                    setCustomView(root)
                    setPosButton("OK") {
                        if (run) fragment.onIconPressed(style, reversed)
                        val value = downloadNo.text.toString().toIntOrNull()
                        if (value != null && value > 0) {
                            fragment.multiDownload(value)
                        }
                        if (refresh) fragment.loadChapters(source, true)
                    }
                    setNegButton("Cancel") {
                        if (refresh) fragment.loadChapters(source, true)
                    }
                    show()
                }
            }
        }
        handleChapters()
    }

    fun updateChips(limit: Int, names: Array<String>, arr: Array<Int>, selected: Int = 0) {
        val binding = _binding
        if (binding != null) {
            nox.kiwami.util.PaginationChipHelper.buildChips(
                context = fragment.requireContext(),
                chipGroup = binding.mediaSourceChipGroup,
                scrollView = binding.mediaWatchChipScroll,
                limit = limit,
                names = names,
                arr = arr,
                selected = selected,
                onChipClicked = { position, start, end ->
                    fragment.onChipClicked(position, start, end)
                }
            )
        }
    }

    fun clearChips() {
        _binding?.mediaSourceChipGroup?.removeAllViews()
    }

    private var autoSelect = true

    private val continueThreshold = 0.8f

    private fun savedProgressRatio(chapterNumber: String): Float? {
        val clean = MediaNameAdapter.findChapterNumber(chapterNumber)?.let {
            if (it % 1 == 0f) it.toInt().toString() else it.toString()
        }
        val curr = PrefManager.getNullableCustomVal("${media.id}_$chapterNumber", null, Long::class.java)
            ?: clean?.let { PrefManager.getNullableCustomVal("${media.id}_$it", null, Long::class.java) }
        val max = PrefManager.getNullableCustomVal("${media.id}_${chapterNumber}_max", null, Long::class.java)
            ?: clean?.let { PrefManager.getNullableCustomVal("${media.id}_${it}_max", null, Long::class.java) }
        if (curr == null || max == null || max <= 0L) return null
        return curr.toFloat() / max.toFloat()
    }

    private fun appChapterNumber(): Int =
        PrefManager.getNullableCustomVal("${media.id}_current_chp", null, String::class.java)
            ?.let { MediaNameAdapter.findChapterNumber(it)?.toInt() ?: it.toIntOrNull() }
            ?: PrefManager.getNullableCustomVal("${media.id}_current_chp_num", null, String::class.java)?.toIntOrNull()
            ?: 1

    fun continueTargetNumber(chapters: Collection<MangaChapter>): Float {
        val anilistEp = (media.userProgress ?: 0).plus(1)
        val appEp = appChapterNumber()
        val appChapter = chapters.find { ch ->
            MediaNameAdapter.findChapterNumber(ch.number)?.toInt() == appEp
        } ?: chapters.elementAtOrNull(appEp - 1)
        val appChapterActive = appChapter != null &&
            (savedProgressRatio(appChapter.number)?.let { it < continueThreshold } ?: false)
        return (if (anilistEp > appEp && !appChapterActive) anilistEp else appEp).toFloat()
    }

    fun handleChapters() {

        val binding = _binding
        if (binding != null) {
            if (media.manga?.chapters != null && media.manga.chapters!!.isNotEmpty()) {
                val filteredChapters = media.manga.chapters!!.filter { chapter ->
                    if (mangaReadSources[media.selected!!.sourceIndex] is OfflineMangaParser) {
                        true
                    } else {
                        chapter.value.scanlator !in hiddenScanlators
                    }
                }
                val appEp = appChapterNumber()

                val maxAvailableChapter = filteredChapters.values.maxOfOrNull {
                    MediaNameAdapter.findChapterNumber(it.number) ?: 0f
                } ?: filteredChapters.size.toFloat()

                if (media.userProgress != null && media.userProgress!!.toFloat() >= maxAvailableChapter && (media.userProgress ?: 0) >= appEp) {
                    binding.sourceContinue.visibility = View.GONE
                    binding.sourceProgressBar.visibility = View.GONE
                    return
                }

                val targetChpNum = continueTargetNumber(filteredChapters.values)

                var continueChap = filteredChapters.values.find { ch ->
                    val num = MediaNameAdapter.findChapterNumber(ch.number)
                    num != null && (num == targetChpNum || num.toInt() == targetChpNum.toInt())
                } ?: filteredChapters.values.elementAtOrNull(targetChpNum.toInt() - 1)

                if (continueChap != null) {
                    handleProgress(
                        binding.itemMediaProgressCont,
                        binding.itemMediaProgress,
                        binding.itemMediaProgressEmpty,
                        media.id,
                        continueChap.number
                    )
                    val progressWeight = (binding.itemMediaProgress.layoutParams as LinearLayout.LayoutParams).weight
                    if (progressWeight > continueThreshold) {
                        val chapterList = filteredChapters.values.toList()
                        val currIdx = chapterList.indexOf(continueChap)
                        if (currIdx != -1 && currIdx + 1 < chapterList.size) {
                            continueChap = chapterList[currIdx + 1]
                            handleProgress(
                                binding.itemMediaProgressCont,
                                binding.itemMediaProgress,
                                binding.itemMediaProgressEmpty,
                                media.id,
                                continueChap.number
                            )
                        } else {
                            continueChap = null
                        }
                    }

                    if (continueChap != null) {
                        binding.sourceContinue.visibility = View.VISIBLE
                        binding.itemMediaImage.loadImage(media.banner ?: media.cover)
                        binding.mediaSourceContinueText.text =
                            binding.root.context.getString(
                                R.string.continue_chapter,
                                continueChap.number,
                                if (!continueChap.title.isNullOrEmpty()) continueChap.title else ""
                            )
                        binding.sourceContinue.setOnClickListener {
                            fragment.onMangaChapterClick(continueChap)
                        }
                        if (fragment.continueEp) {
                            if ((binding.itemMediaProgress.layoutParams as LinearLayout.LayoutParams).weight < continueThreshold) {
                                binding.sourceContinue.performClick()
                                fragment.continueEp = false
                            }
                        }
                    } else {
                        binding.sourceContinue.visibility = View.GONE
                    }
                } else {
                    binding.sourceContinue.visibility = View.GONE
                }

                binding.sourceProgressBar.visibility = View.GONE

                val sourceFound = filteredChapters.isNotEmpty()
                sourceCard?.onResult(sourceFound, filteredChapters.size)
                countLabel?.show(if (sourceFound) filteredChapters.size else 0)
                val isDownloadedSource =
                    mangaReadSources[media.selected!!.sourceIndex] is OfflineMangaParser

                if (isDownloadedSource) {
                    binding.sourceNotFound.text = if (sourceFound) {
                        binding.root.context.getString(R.string.source_not_found)
                    } else {
                        binding.root.context.getString(R.string.download_not_found)
                    }
                } else {
                    binding.sourceNotFound.text =
                        binding.root.context.getString(R.string.source_not_found)
                }

                binding.sourceNotFound.isGone = sourceFound
                binding.faqbutton.isGone = sourceFound


                if (!sourceFound && PrefManager.getVal(PrefName.SearchSources) && autoSelect) {
                    if (binding.mediaSource.adapter.count > media.selected!!.sourceIndex + 1) {
                        val nextIndex = media.selected!!.sourceIndex + 1
                        binding.mediaSource.setText(
                            binding.mediaSource.adapter
                                .getItem(nextIndex).toString(), false
                        )
                        fragment.onSourceChange(nextIndex, true).apply {
                            binding.mediaSourceTitle.text = showUserText
                            showUserTextListener =
                                { MainScope().launch { binding.mediaSourceTitle.text = it } }
                            setLanguageList(0, nextIndex)
                        }
                        val invalidate = nextIndex == mangaReadSources.names.size - 1
                        fragment.loadChapters(nextIndex, invalidate)
                    }
                }
                binding.mediaSource.setOnClickListener { autoSelect = false }
            } else {
                binding.sourceContinue.visibility = View.GONE
                binding.sourceNotFound.visibility = View.GONE
                binding.faqbutton.visibility = View.GONE
                clearChips()
                binding.sourceProgressBar.visibility = View.VISIBLE
                sourceCard?.onLoading()
                countLabel?.hide()
            }
        }
    }

    private fun setLanguageList(lang: Int, source: Int) {
        val binding = _binding
        if (mangaReadSources is MangaSources) {
            val parser = mangaReadSources[source] as? DynamicMangaParser
            if (parser != null) {
                (mangaReadSources[source] as? DynamicMangaParser)?.let { ext ->
                    ext.sourceLanguage = lang
                }
                try {
                    binding?.mediaSourceLanguage?.setText(parser.extension.sources[lang].lang)
                } catch (e: IndexOutOfBoundsException) {
                    binding?.mediaSourceLanguage?.setText(
                        parser.extension.sources.firstOrNull()?.lang ?: "Unknown"
                    )
                }
                val adapter = ArrayAdapter(
                    fragment.requireContext(),
                    R.layout.item_dropdown,
                    parser.extension.sources.map { LanguageMapper.getLanguageName(it.lang) }
                )
                val items = adapter.count
                binding?.mediaSourceLanguageContainer?.isVisible = items > 1

                binding?.mediaSourceLanguage?.setAdapter(adapter)
                val names = parser.extension.sources.map { LanguageMapper.getLanguageName(it.lang) }
                val codes = parser.extension.sources.map { it.lang.uppercase() }
                binding?.chipLanguage?.setIcon(R.drawable.ic_round_translate_24)
                binding?.chipLanguage?.setOptions(names, codes, lang) { i ->
                    binding?.mediaSourceLanguage?.onItemClickListener
                        ?.onItemClick(null, null, i, i.toLong())
                }
                binding?.chipLanguage?.isVisible = items > 1
                syncChipRow()
            }
        }
    }

    fun openSourcePicker() {
        _binding?.sourceCard?.performClick()
    }

    private fun syncChipRow() {
        val b = _binding ?: return
        b.sourceChipsRow.isVisible = b.chipLanguage.isVisible || b.chipScanlator.isVisible
    }

    fun updateScanlatorDropdown() {
        val binding = _binding ?: return
        if (options.size > 1) {
            binding.mediaSourceScanlatorContainer.visibility = View.VISIBLE
            binding.mediaSourceScanlatorContainer.hint = currActivity()?.getString(R.string.scanlator)

            val allText = currActivity()?.getString(R.string.all_scanlators) ?: "All Scanlators"
            val dropdownItems = listOf(allText) + options

            val selectedText = if (hiddenScanlators.isEmpty() || hiddenScanlators.size >= options.size) {
                allText
            } else {
                val shown = options.firstOrNull { it !in hiddenScanlators }
                shown ?: allText
            }

            binding.mediaSourceScanlator.setText(selectedText, false)
            val adapter = ArrayAdapter(
                fragment.requireContext(),
                R.layout.item_dropdown,
                dropdownItems
            )
            binding.mediaSourceScanlator.setAdapter(adapter)
            binding.mediaSourceScanlator.setOnItemClickListener { _, _, i, _ ->
                if (i == 0) {
                    hiddenScanlators.clear()
                } else {
                    val selectedOption = options.getOrNull(i - 1)
                    hiddenScanlators.clear()
                    if (selectedOption != null) {
                        hiddenScanlators.addAll(options.filter { it != selectedOption })
                    }
                }
                fragment.onScanlatorChange(hiddenScanlators)
                scanlatorSelectionListener?.onScanlatorsSelected()
            }
            val selectedIndex =
                if (selectedText == allText) 0 else (options.indexOf(selectedText) + 1).coerceAtLeast(0)
            binding.chipScanlator.setIcon(R.drawable.ic_round_filter_list_24)
            binding.chipScanlator.setOptions(dropdownItems, dropdownItems, selectedIndex) { i ->
                binding.mediaSourceScanlator.onItemClickListener
                    ?.onItemClick(null, null, i, i.toLong())
            }
            binding.chipScanlator.isVisible = true
        } else {
            binding.mediaSourceScanlatorContainer.visibility = View.GONE
            binding.chipScanlator.isVisible = false
        }
        syncChipRow()
    }

    override fun getItemCount(): Int = 1

    inner class ViewHolder(val binding: ItemMediaSourceBinding) :
        RecyclerView.ViewHolder(binding.root)
}

interface ScanlatorSelectionListener {
    fun onScanlatorsSelected()
}
