package nox.kiwami.media.comments

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context.INPUT_METHOD_SERVICE
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.animation.doOnEnd
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import nox.kiwami.R
import nox.kiwami.buildMarkwon
import nox.kiwami.connections.anilist.Anilist
import nox.kiwami.connections.comments.Comment
import nox.kiwami.connections.comments.CommentResponse
import nox.kiwami.connections.comments.CommentsAPI
import nox.kiwami.databinding.DialogEdittextBinding
import nox.kiwami.databinding.FragmentCommentsBinding
import nox.kiwami.loadImage
import nox.kiwami.media.MediaNameAdapter
import nox.kiwami.media.anime.getEpisode
import nox.kiwami.media.MediaDetailsActivity
import nox.kiwami.media.MediaDetailsViewModel
import nox.kiwami.notifications.comment.MediaNameFetch
import nox.kiwami.setBaseline
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import nox.kiwami.snackString
import nox.kiwami.util.Logger
import nox.kiwami.util.customAlertDialog
import com.xwray.groupie.GroupieAdapter
import com.xwray.groupie.Section
import io.noties.markwon.editor.MarkwonEditor
import io.noties.markwon.editor.MarkwonEditorTextWatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@SuppressLint("ClickableViewAccessibility")
class CommentsFragment : Fragment() {
    private var _binding: FragmentCommentsBinding? = null
    val binding get() = _binding!!
    val activity: MediaDetailsActivity get() = requireActivity() as MediaDetailsActivity
    private var interactionState = InteractionState.NONE
    private var commentWithInteraction: CommentItem? = null
    private val section = Section()
    private val adapter = GroupieAdapter()
    private var tag: Int? = null
    private var filterTag: Int? = null
    private var mediaId: Int = -1
    var mediaName: String = ""
    private var backgroundColor: Int = 0
    var pagesLoaded = 1
    var totalPages = 1
    private var userProgress: Int? = null
    private var totalEpisodesOrChapters: Int? = null
    private var isAnime: Boolean = true
    private var commentsLoaded = false
    private var isAutoFilterOn = false
    private var isSpoilerMode = false
    private var markwonTextWatcher: TextWatcher? = null
    private var limitTextWatcher: TextWatcher? = null
    private var headerPump: RecyclerView.AdapterDataObserver? = null
    private var composer: CommentComposerController? = null
    private var lastHeaderPlay = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCommentsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding?.commentsList?.adapter = null
        headerPump?.let { adapter.unregisterAdapterDataObserver(it) }
        headerPump = null
        _binding = null
        (activity as? MediaDetailsActivity)?.binding?.let { actBinding ->
            markwonTextWatcher?.let { actBinding.commentInput.removeTextChangedListener(it) }
            limitTextWatcher?.let { actBinding.commentInput.removeTextChangedListener(it) }
            composer?.release()
            actBinding.commentInput.onFocusChangeListener = null
            actBinding.commentLabel.setOnClickListener(null)
            actBinding.commentSpoiler.setOnClickListener(null)
            actBinding.commentGif.setOnClickListener(null)
            actBinding.commentSend.setOnClickListener(null)
        }
        markwonTextWatcher = null
        limitTextWatcher = null
        composer = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The host can be mid-bail after a process death; it has no views to anchor to.
        if (!activity.bindingReady) return
        val baselineAnchor = activity.binding.mediaBottomBarContainer ?: activity.binding.commentMessageContainer
        baselineAnchor?.let {
            // If it's the unified container, it already has navBarHeight padding, so don't include it again.
            val includeSystemPaddings = it != activity.binding.mediaBottomBarContainer
            binding.commentsList.setBaseline(it, includeSystemNavBar = includeSystemPaddings)
        }
        val mediaId = arguments?.getInt("mediaId") ?: -1
        mediaName = arguments?.getString("mediaName") ?: "unknown"
        if (mediaId == -1) {
            snackString("Invalid Media ID")
            return
        }
        this.mediaId = mediaId
        if (mediaName.isNotBlank() && !mediaName.equals("unknown", ignoreCase = true)) {
            MediaNameFetch.cacheMedia(mediaId, mediaName)
        }
        backgroundColor = (binding.root.background as? ColorDrawable)?.color ?: 0

        val markwon = buildMarkwon(activity, fragment = this@CommentsFragment)

        activity.binding.commentUserAvatar.loadImage(Anilist.avatar)
        val markwonEditor = MarkwonEditor.create(markwon)
        val watcher = MarkwonEditorTextWatcher.withProcess(markwonEditor)
        markwonTextWatcher = watcher
        activity.binding.commentInput.addTextChangedListener(watcher)

        val isOfflineOrLocal = !nox.kiwami.isOnline(activity)

        binding.commentsRefresh.setOnRefreshListener {
            if (!reloadComments()) binding.commentsRefresh.isRefreshing = false
        }
        binding.commentsRetry.setOnClickListener {
            if (!reloadComments()) shake(binding.commentsOfflineText)
        }
        binding.commentsEmptyAction.setOnClickListener { reloadComments() }

        binding.commentsList.adapter = adapter
        binding.commentsList.layoutManager = LinearLayoutManager(activity)
        (binding.commentsList.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        adapter.add(section)
        setupHeader()

        val model: MediaDetailsViewModel by activityViewModels()
        model.getMedia().observe(viewLifecycleOwner) { newMedia ->
            if (newMedia != null && newMedia.id != 0) {
                isAnime = newMedia.anime != null
                userProgress = newMedia.userProgress
                totalEpisodesOrChapters = if (isAnime)
                    newMedia.anime?.totalEpisodes
                else
                    newMedia.manga?.totalChapters
                updateCurrentProgressButton()

                if (!commentsLoaded || newMedia.id != this.mediaId) {
                    this.mediaId = newMedia.id
                    commentsLoaded = true

                    if (isOfflineOrLocal) {
                        binding.commentsOfflineText.visibility = View.VISIBLE
                        binding.commentsList.visibility = View.GONE
                        setHeaderControlsVisible(false)
                        binding.commentsProgressBar.visibility = View.GONE
                        activity.binding.commentMessageContainer.visibility = View.GONE
                    } else if (CommentsAPI.authToken != null) {
                        lifecycleScope.launch {
                            val commentId = arguments?.getInt("commentId")
                            if (commentId != null && commentId > 0) {
                                loadCommentThread(commentId)
                            } else {
                                loadAndDisplayComments()
                            }
                        }
                    } else {
                        activity.binding.commentMessageContainer.visibility = View.GONE
                        binding.commentsProgressBar.visibility = View.GONE
                        showEmptyState("Comments unavailable", "Log in to read and post comments.", true)
                    }
                }
            }
        }

        binding.commentSort.setOnClickListener { sortView ->
            val popup = PopupMenu(activity, sortView)
            popup.setOnMenuItemClickListener { item ->
                val sortOrder = when (item.itemId) {
                    R.id.comment_sort_newest -> "newest"
                    R.id.comment_sort_oldest -> "oldest"
                    R.id.comment_sort_highest_rated -> "highest_rated"
                    R.id.comment_sort_lowest_rated -> "lowest_rated"
                    else -> return@setOnMenuItemClickListener false
                }
                PrefManager.setVal(PrefName.CommentSortOrder, sortOrder)
                updateSortChip()
                viewLifecycleOwner.lifecycleScope.launch {
                    loadAndDisplayComments()
                    activity.binding.commentReplyToContainer.visibility = View.GONE
                }
                binding.commentsList.scrollToPosition(0)
                true
            }
            popup.inflate(R.menu.comments_sort_menu)
            popup.show()
        }
        binding.openRules.setOnClickListener {
            activity.customAlertDialog().apply {
                setTitle("Commenting Rules")
                    .setMessage(
                        "🚨 BREAK ANY RULE = YOU'RE GONE\n\n" +
                                "1. NO RACISM, DISCRIMINATION, OR HATE SPEECH\n" +
                                "2. NO SPAMMING OR SELF-PROMOTION\n" +
                                "3. ABSOLUTELY NO NSFW CONTENT\n" +
                                "4. ENGLISH ONLY – NO EXCEPTIONS\n" +
                                "5. NO IMPERSONATION, HARASSMENT, OR ABUSE\n" +
                                "6. NO ILLEGAL CONTENT OR EXTREME DISRESPECT TOWARDS ANY FANDOM\n" +
                                "7. DO NOT REQUEST OR SHARE REPOSITORIES/EXTENSIONS\n" +
                                "8. SPOILERS ALLOWED ONLY WITH SPOILER TAGS AND A WARNING\n" +
                                "9. NO SEXUALIZING OR INAPPROPRIATE COMMENTS ABOUT MINOR CHARACTERS\n" +
                                "10. IF IT'S WRONG, DON'T POST IT!\n\n"
                    )
                setNegButton("I Understand") {}
                show()
            }
        }

        binding.commentFilter.setOnClickListener {
            activity.customAlertDialog().apply {
                val customView = DialogEdittextBinding.inflate(layoutInflater)
                setTitle("Enter a chapter/episode number tag")
                setCustomView(customView.root)
                setPosButton("OK") {
                    val text = customView.dialogEditText.text.toString()
                    filterTag = text.toIntOrNull()
                    updateCurrentProgressButton()
                    lifecycleScope.launch {
                        loadAndDisplayComments()
                    }
                }
                setNeutralButton("Clear") {
                    filterTag = null
                    updateCurrentProgressButton()
                    lifecycleScope.launch {
                        loadAndDisplayComments()
                    }
                }
                setNegButton("Cancel") {}
                show()
            }
        }

        binding.commentCurrentProgress.setOnClickListener {
            val progress = userProgress ?: return@setOnClickListener
            if (progress <= 0) return@setOnClickListener
            if (filterTag != null && filterTag != progress) {
                filterTag = null
                isAutoFilterOn = false
            } else {
                isAutoFilterOn = !isAutoFilterOn
            }
            updateCurrentProgressButton()
            lifecycleScope.launch {
                loadAndDisplayComments()
            }
        }

        binding.commentCurrentProgress.setOnLongClickListener {
            val progress = userProgress ?: return@setOnLongClickListener false
            if (progress <= 0) return@setOnLongClickListener false
            val total = totalEpisodesOrChapters ?: progress
            val maxEp = maxOf(total, progress)
            val label = if (isAnime) "Ep" else "Ch"

            val items = Array(maxEp) { i -> "$label ${i + 1}" }
            val currentSelection = if (filterTag != null) filterTag!! - 1 else progress - 1
            activity.customAlertDialog().apply {
                setTitle("Filter by ${if (isAnime) "Episode" else "Chapter"}")
                singleChoiceItems(items, currentSelection) { selected ->
                    filterTag = selected + 1
                    isAutoFilterOn = true
                    updateCurrentProgressButton()
                    lifecycleScope.launch {
                        loadAndDisplayComments()
                    }
                }
                show()
            }
            true
        }

        var isFetching = false
        binding.commentsList.setOnTouchListener(
            object : View.OnTouchListener {
                override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                    binding.commentsHeader.pump(900)
                    if (event?.action == MotionEvent.ACTION_UP) {
                        if (!binding.commentsList.canScrollVertically(1) && !isFetching &&
                            (binding.commentsList.layoutManager as LinearLayoutManager).findLastVisibleItemPosition() == (binding.commentsList.adapter!!.itemCount - 1)
                        ) {
                            if (pagesLoaded < totalPages && totalPages > 1) {
                                binding.commentBottomRefresh.visibility = View.VISIBLE
                                loadMoreComments()
                                viewLifecycleOwner.lifecycleScope.launch {
                                    kotlinx.coroutines.delay(1000)
                                    withContext(Dispatchers.Main) {
                                        _binding?.commentBottomRefresh?.visibility = View.GONE
                                    }
                                }
                            } else {
                                Logger.log("No more comments")
                            }
                        }
                    }
                    return false
                }

                private fun loadMoreComments() {
                    isFetching = true
                    lifecycleScope.launch {
                        val comments = fetchComments()
                        comments?.comments?.forEach { comment ->
                            updateUIWithComment(comment)
                        }
                        totalPages = comments?.totalPages ?: 1
                        pagesLoaded++
                        isFetching = false
                    }
                }

                private suspend fun fetchComments(): CommentResponse? {
                    return withContext(Dispatchers.IO) {
                        CommentsAPI.getCommentsForId(
                            mediaId,
                            pagesLoaded + 1,
                            getEffectiveFilter(),
                            PrefManager.getVal(PrefName.CommentSortOrder, "newest")
                        )
                    }
                }
                private suspend fun updateUIWithComment(comment: Comment) {
                    withContext(Dispatchers.Main) {
                        section.add(
                            CommentItem(
                                comment,
                                buildMarkwon(activity, fragment = this@CommentsFragment),
                                section,
                                this@CommentsFragment,
                                backgroundColor,
                                0
                            )
                        )
                    }
                }
            })

        val limitWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            }

            override fun afterTextChanged(s: android.text.Editable?) {
                if ((activity.binding.commentInput.text.length) > 300) {
                    activity.binding.commentInput.text.delete(
                        300,
                        activity.binding.commentInput.text.length
                    )
                    snackString("Comment cannot be longer than 300 characters")
                }
            }
        }
        limitTextWatcher = limitWatcher
        activity.binding.commentInput.addTextChangedListener(limitWatcher)

        activity.binding.commentLabel.setOnClickListener {
            activity.customAlertDialog().apply {
                val customView = DialogEdittextBinding.inflate(layoutInflater)
                setTitle("Enter a chapter/episode number tag")
                setCustomView(customView.root)
                setPosButton("OK") {
                    val text = customView.dialogEditText.text.toString()
                    tag = text.toIntOrNull()
                    if (tag == null) {
                        activity.binding.commentLabel.background = ResourcesCompat.getDrawable(
                            resources,
                            R.drawable.ic_label_off_24,
                            null
                        )
                    } else {
                        activity.binding.commentLabel.background = ResourcesCompat.getDrawable(
                            resources,
                            R.drawable.ic_label_24,
                            null
                        )
                    }
                }
                setNeutralButton("Clear") {
                    tag = null
                    activity.binding.commentLabel.background = ResourcesCompat.getDrawable(
                        resources,
                        R.drawable.ic_label_off_24,
                        null
                    )
                }
                setNegButton("Cancel") {
                    tag = null
                    activity.binding.commentLabel.background = ResourcesCompat.getDrawable(
                        resources,
                        R.drawable.ic_label_off_24,
                        null
                    )
                }
                show()
            }
        }

        composer = CommentComposerController(activity.binding).also { it.bind() }

        activity.binding.commentSpoiler.setOnClickListener {
            isSpoilerMode = !isSpoilerMode
            activity.binding.commentSpoiler.alpha = if (isSpoilerMode) 1f else 0.5f
            activity.binding.commentSpoiler.setImageResource(
                if (isSpoilerMode) R.drawable.format_spoiler_24
                else R.drawable.ic_round_remove_red_eye_24
            )
        }

        activity.binding.commentGif.setOnClickListener {
            val gifPicker = GifPickerBottomDialog.newInstance()
            gifPicker.setOnGifSelectedListener { gifUrl ->
                val currentText = activity.binding.commentInput.text.toString()
                val gifMarkdown = "![gif]($gifUrl)"
                val newText = if (currentText.isEmpty()) gifMarkdown else "$currentText\n$gifMarkdown"
                activity.binding.commentInput.setText(newText)
                activity.binding.commentInput.setSelection(newText.length)
            }
            gifPicker.show(childFragmentManager, "gifPicker")
        }

        activity.binding.commentSend.setOnClickListener {
            it.performHapticFeedback(
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                    HapticFeedbackConstants.CONFIRM
                else
                    HapticFeedbackConstants.CONTEXT_CLICK
            )
            if (CommentsAPI.isBanned) {
                snackString("You are banned from commenting :(")
                return@setOnClickListener
            }

            if (PrefManager.getVal(PrefName.FirstComment)) {
                showCommentRulesDialog()
            } else {
                showTagDialogThenProcess()
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onResume() {
        super.onResume()
        tag = null
        if (_binding != null) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastHeaderPlay > 1500) {
                lastHeaderPlay = now
                binding.commentsHeader.reveal()
                animateHeaderIn()
            }
        }
        section.groups.forEach {
            if (it is CommentItem && it.containsGif()) {
                it.notifyChanged()
            }
        }
    }

    enum class InteractionState {
        NONE, EDIT, REPLY
    }

    fun onTimestampClicked(tag: String?, timestampMs: Long) {
        val targetTag = tag ?: filterTag?.toString()
        if (targetTag == null) {
            snackString("No episode tagged in this comment")
            return
        }
        val model: MediaDetailsViewModel by activityViewModels()
        val currentMedia = model.getMedia().value ?: return

        if (isAnime) {
            val ep = currentMedia.anime?.episodes?.getEpisode(targetTag)
            if (ep != null) {
                val cleanEp = MediaNameAdapter.findEpisodeNumber(targetTag)?.let {
                    if (it % 1 == 0f) it.toInt().toString() else it.toString()
                }
                PrefManager.setCustomVal("${currentMedia.id}_$targetTag", timestampMs)
                if (cleanEp != null) {
                    PrefManager.setCustomVal("${currentMedia.id}_$cleanEp", timestampMs)
                }
                nox.kiwami.media.anime.ExoplayerView.targetStartPosition = timestampMs
                model.onEpisodeClick(currentMedia, targetTag, childFragmentManager, true)
            } else {
                snackString("Episode $targetTag not found for this provider")
            }
        } else {
            onTagClicked(targetTag)
        }
    }

    fun onTagClicked(tag: String) {
        val model: MediaDetailsViewModel by activityViewModels()
        val currentMedia = model.getMedia().value ?: return

        if (isAnime) {
            val ep = currentMedia.anime?.episodes?.getEpisode(tag)
            if (ep != null) {
                model.onEpisodeClick(currentMedia, tag, childFragmentManager, true)
            } else {
                snackString("Episode $tag not found for this provider")
            }
        } else {
            val selected = currentMedia.selected
            if (selected?.sourceIndex == null) {
                snackString("Please select an extension first")
                return
            }
            val sourceIndex = selected.sourceIndex

            val isNovel = currentMedia.format == "NOVEL"
            if (isNovel) {
                val chapters = model.getNovelChapters().value?.get(sourceIndex)
                val chpResponse = chapters?.find { it.extra?.get("chapterNumber") == tag }
                if (chpResponse != null) {
                    lifecycleScope.launch {
                        model.loadBook(chpResponse, sourceIndex)
                        val intent = android.content.Intent(activity, nox.kiwami.media.novel.novelreader.NovelReaderActivity::class.java)
                        startActivity(intent)
                    }
                } else {
                    snackString("Novel chapter $tag not found")
                }
            } else {
                val manga = currentMedia.manga ?: run {
                    snackString("Manga not available")
                    return
                }
                val cachedChapters = model.getMangaChapters().value?.get(sourceIndex)
                if (manga.chapters.isNullOrEmpty() && cachedChapters != null) {
                    manga.chapters = cachedChapters
                }

                val chp = manga.chapters?.values?.find { chapterMatchesTag(it.number, tag) }
                if (chp != null) {
                    model.continueMedia = false
                    manga.selectedChapter = chp
                    model.saveSelected(currentMedia.id, selected)
                    
                    nox.kiwami.media.manga.mangareader.ChapterLoaderDialog.newInstance(chp, true)
                        .show(childFragmentManager, "dialog")
                } else {
                    lifecycleScope.launch {
                        snackString("Loading chapter for tag...")
                        model.loadMangaChapters(currentMedia, sourceIndex)
                        val refreshedChapters = model.getMangaChapters().value?.get(sourceIndex)
                        if (refreshedChapters != null) {
                            manga.chapters = refreshedChapters
                        }
                        val foundChp =
                            manga.chapters?.values?.find { chapterMatchesTag(it.number, tag) }
                        if (foundChp != null) {
                            model.continueMedia = false
                            manga.selectedChapter = foundChp
                            model.saveSelected(currentMedia.id, selected)
                            nox.kiwami.media.manga.mangareader.ChapterLoaderDialog.newInstance(foundChp, true)
                                .show(childFragmentManager, "dialog")
                        } else {
                            snackString("Chapter $tag not found for this provider")
                        }
                    }
                }
            }
        }
    }

    private fun chapterMatchesTag(chapterNumber: String, tag: String): Boolean {
        if (chapterNumber == tag) return true

        val chapterValue = MediaNameAdapter.findChapterNumber(chapterNumber)
        val tagValue = MediaNameAdapter.findChapterNumber(tag)
        return chapterValue != null && tagValue != null && chapterValue == tagValue
    }

    private fun getEffectiveFilter(): Int? = when {
        filterTag != null -> filterTag
        isAutoFilterOn && userProgress != null && userProgress!! > 0 -> userProgress
        else -> null
    }

    private fun reloadComments(): Boolean {
        if (!nox.kiwami.isOnline(activity)) return false
        binding.commentsOfflineText.visibility = View.GONE
        binding.commentsList.visibility = View.VISIBLE
        setHeaderControlsVisible(true)
        activity.binding.commentMessageContainer.visibility =
            if (CommentsAPI.authToken != null) View.VISIBLE else View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            loadAndDisplayComments()
            _binding?.commentsRefresh?.isRefreshing = false
        }
        activity.binding.commentReplyToContainer.visibility = View.GONE
        return true
    }

    private fun shake(view: View) {
        ObjectAnimator.ofFloat(
            view, View.TRANSLATION_X, 0f, -14f, 14f, -10f, 10f, -5f, 5f, 0f
        ).setDuration(420).start()
    }

    private fun showEmptyState(title: String, subtitle: String, retry: Boolean = false) {
        val state = binding.commentsEmptyState
        binding.commentsEmptyTitle.text = title
        binding.commentsEmptySubtitle.text = subtitle
        binding.commentsEmptyAction.visibility = if (retry) View.VISIBLE else View.GONE
        state.animate().cancel()
        state.visibility = View.VISIBLE
        state.alpha = 0f
        state.scaleX = 0.92f
        state.scaleY = 0.92f
        state.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(420)
            .setInterpolator(OvershootInterpolator(1.6f))
            .start()
        binding.commentsEmptyIcon.animate().cancel()
        binding.commentsEmptyIcon.rotation = -14f
        binding.commentsEmptyIcon.animate()
            .rotation(0f)
            .setStartDelay(120)
            .setDuration(520)
            .setInterpolator(OvershootInterpolator(3f))
            .start()
    }

    private fun hideEmptyState() {
        val state = _binding?.commentsEmptyState ?: return
        if (state.visibility != View.VISIBLE) return
        state.animate().cancel()
        state.animate()
            .alpha(0f)
            .setDuration(160)
            .withEndAction { _binding?.commentsEmptyState?.visibility = View.GONE }
            .start()
    }

    private fun setupHeader() {
        val header = binding.commentsHeader
        val list = binding.commentsList
        val density = resources.displayMetrics.density
        header.bind(
            list,
            binding.commentsRefresh,
            resolveColorAttr(android.R.attr.colorBackground),
            ColorUtils.setAlphaComponent(
                resolveColorAttr(com.google.android.material.R.attr.colorOnBackground),
                0x1F
            )
        )
        header.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val height = bottom - top
            if (_binding != null && height > 0 && list.paddingTop != height) {
                list.updatePadding(top = height)
                binding.commentsProgressBar.updatePadding(top = height)
                binding.commentsEmptyState.updatePadding(top = height)
                binding.commentsOfflineText.updatePadding(top = height)
                binding.commentsRefresh.post {
                    val refresh = _binding?.commentsRefresh ?: return@post
                    if (!refresh.isRefreshing) {
                        refresh.setProgressViewOffset(
                            false,
                            height - (48 * density).toInt(),
                            height + (24 * density).toInt()
                        )
                    }
                }
            }
        }

        val pump = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = header.pump(700)
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = header.pump(700)
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = header.pump(700)
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = header.pump(700)
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) =
                header.pump(700)
        }
        headerPump?.let { adapter.unregisterAdapterDataObserver(it) }
        headerPump = pump
        adapter.registerAdapterDataObserver(pump)

        listOf(
            binding.commentSort,
            binding.commentFilter,
            binding.commentCurrentProgress,
            binding.openRules
        ).forEach { pressScale(it) }

        updateSortChip()
        updateCurrentProgressButton()
    }

    private fun setHeaderControlsVisible(visible: Boolean) {
        val state = if (visible) View.VISIBLE else View.GONE
        binding.openRules.visibility = state
        binding.commentChipsScroll.visibility = state
    }

    private fun animateHeaderIn() {
        val density = resources.displayMetrics.density
        listOf<View>(binding.commentsTitle, binding.openRules, binding.commentChipsScroll)
            .forEachIndexed { index, view ->
                view.alpha = 0f
                view.translationY = 10f * density
                view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(index * 70L)
                    .setDuration(520)
                    .setInterpolator(DecelerateInterpolator(1.8f))
                    .start()
            }
    }

    private fun pressScale(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.94f).scaleY(0.94f)
                    .setDuration(90).setInterpolator(DecelerateInterpolator()).start()

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f)
                    .scaleY(1f).setDuration(280).setInterpolator(OvershootInterpolator(2.4f))
                    .start()
            }
            false
        }
    }

    private fun styleChip(chip: View, fill: Int, stroke: Int) {
        val density = resources.displayMetrics.density
        val radius = 20f * density
        val shape = GradientDrawable().apply {
            cornerRadius = radius
            setColor(fill)
            setStroke(density.toInt().coerceAtLeast(1), stroke)
        }
        val mask = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.BLACK)
        }
        val ripple = ColorStateList.valueOf(
            ColorUtils.setAlphaComponent(
                resolveColorAttr(com.google.android.material.R.attr.colorOnBackground),
                0x2E
            )
        )
        chip.background = RippleDrawable(ripple, shape, mask)
    }

    private fun tintChip(label: TextView, icon: ImageView?, color: Int) {
        label.setTextColor(color)
        icon?.imageTintList = ColorStateList.valueOf(color)
    }

    private fun styleChipIdle(chip: View, label: TextView, icon: ImageView?) {
        val onBackground = resolveColorAttr(com.google.android.material.R.attr.colorOnBackground)
        styleChip(
            chip,
            ColorUtils.setAlphaComponent(onBackground, 0x14),
            ColorUtils.setAlphaComponent(onBackground, 0x2E)
        )
        tintChip(label, icon, onBackground)
    }

    private fun styleChipActive(chip: View, label: TextView, icon: ImageView?) {
        val primary = resolveColorAttr(androidx.appcompat.R.attr.colorPrimary)
        styleChip(
            chip,
            ColorUtils.setAlphaComponent(primary, 0x2E),
            ColorUtils.setAlphaComponent(primary, 0xB3)
        )
        tintChip(label, icon, primary)
    }

    private fun updateSortChip() {
        val order: String = PrefManager.getVal(PrefName.CommentSortOrder, "newest")
        binding.commentSortLabel.text = when (order) {
            "oldest" -> "Oldest"
            "highest_rated" -> "Top rated"
            "lowest_rated" -> "Lowest rated"
            else -> "Newest"
        }
        styleChipIdle(binding.commentSort, binding.commentSortLabel, binding.commentSortIcon)
    }

    private fun updateFilterChip() {
        val progress = userProgress ?: 0
        val manual = filterTag
        if (progress <= 0 && manual != null) {
            binding.commentFilterLabel.text = "${if (isAnime) "Ep" else "Ch"} $manual"
            styleChipActive(binding.commentFilter, binding.commentFilterLabel, binding.commentFilterIcon)
        } else {
            binding.commentFilterLabel.text = if (isAnime) "Episode" else "Chapter"
            styleChipIdle(binding.commentFilter, binding.commentFilterLabel, binding.commentFilterIcon)
        }
    }

    private fun updateCurrentProgressButton() {
        updateFilterChip()
        val progress = userProgress ?: 0
        if (progress <= 0) {
            binding.commentCurrentProgress.visibility = View.GONE
            return
        }
        val label = if (isAnime) "Ep" else "Ch"
        val isManualFilter = filterTag != null && filterTag != progress
        val activeFilter = filterTag ?: progress

        val badge = binding.commentCurrentProgress
        when {
            isManualFilter -> {
                val primary = resolveColorAttr(androidx.appcompat.R.attr.colorPrimary)
                badge.text = "$label $activeFilter  ✕"
                badge.setTextColor(
                    resolveColorAttr(com.google.android.material.R.attr.colorOnPrimary)
                )
                styleChip(badge, primary, primary)
            }

            isAutoFilterOn -> {
                badge.text = "$label $progress"
                styleChipActive(badge, badge, null)
            }

            else -> {
                badge.text = "$label $progress"
                styleChipIdle(badge, badge, null)
            }
        }
        badge.visibility = View.VISIBLE
    }

    private fun resolveColorAttr(attr: Int): Int {
        val typedArray = activity.obtainStyledAttributes(intArrayOf(attr))
        val color = typedArray.getColor(0, 0)
        typedArray.recycle()
        return color
    }

    private fun showTagDialogThenProcess() {
        if (interactionState == InteractionState.EDIT) {
            processComment()
            return
        }

        val commentText = activity.binding.commentInput.text.toString()
        if (commentText.isEmpty()) {
            snackString("Comment cannot be empty")
            return
        }

        val label = if (isAnime) "episode" else "chapter"
        val total = totalEpisodesOrChapters
        val defaultProgress = userProgress ?: 0

        activity.customAlertDialog().apply {
            val customView = DialogEdittextBinding.inflate(layoutInflater)
            if (defaultProgress > 0) {
                customView.dialogEditText.setText(defaultProgress.toString())
            }
            customView.dialogEditText.hint = if (total != null && total > 0)
                "1–$total"
            else
                "$label number"
            setTitle("Tag $label (optional)")
            setCustomView(customView.root)
            setPosButton("Send") {
                val entered = customView.dialogEditText.text.toString().toIntOrNull()
                if (entered != null && total != null && total > 0 && entered > total) {
                    snackString("Tag cannot exceed total ${label}s ($total)")
                    tag = null
                } else {
                    tag = entered
                }
                activity.binding.commentLabel.background = if (tag != null)
                    ResourcesCompat.getDrawable(resources, R.drawable.ic_label_24, null)
                else
                    ResourcesCompat.getDrawable(resources, R.drawable.ic_label_off_24, null)
                processComment()
            }
            setNeutralButton("No tag") {
                tag = null
                activity.binding.commentLabel.background =
                    ResourcesCompat.getDrawable(resources, R.drawable.ic_label_off_24, null)
                processComment()
            }
            setNegButton(R.string.cancel) {}
            show()
        }
    }

    private suspend fun loadAndDisplayComments() {
        hideEmptyState()
        binding.commentsHeader.reveal()
        binding.commentsProgressBar.visibility = View.VISIBLE
        binding.commentsList.visibility = View.GONE
        section.clear()
        pagesLoaded = 1

        val sortOrder = PrefManager.getVal(PrefName.CommentSortOrder, "newest")
        val effectiveFilter = getEffectiveFilter()
        
        val comments = withContext(Dispatchers.IO) {
            CommentsAPI.getCommentsForId(
                mediaId,
                page = 1,
                tag = effectiveFilter,
                sort = sortOrder
            )
        }

        sortComments(comments?.comments).forEach { comment ->
            withContext(Dispatchers.Main) {
                section.add(
                    CommentItem(
                        comment,
                        buildMarkwon(activity, fragment = this@CommentsFragment),
                        section,
                        this@CommentsFragment,
                        backgroundColor,
                        0
                    )
                )
            }
        }

        totalPages = comments?.totalPages ?: 1
        binding.commentsProgressBar.visibility = View.GONE
        binding.commentsList.visibility = View.VISIBLE
        if (comments == null) {
            showEmptyState("Couldn\u2019t load comments", "Check your connection and try again.", true)
        } else if (comments.comments.isEmpty()) {
            val short = if (isAnime) "Ep" else "Ch"
            val noun = if (isAnime) "episode" else "chapter"
            if (effectiveFilter != null) {
                showEmptyState(
                    "No comments for $short $effectiveFilter",
                    "Be the first to comment on this $noun."
                )
            } else {
                showEmptyState("No comments yet", "Be the first to start the conversation.")
            }
        }
    }

    private suspend fun loadCommentThread(commentId: Int) {
        hideEmptyState()
        binding.commentsHeader.reveal()
        binding.commentsProgressBar.visibility = View.VISIBLE
        binding.commentsList.visibility = View.GONE
        section.clear()

        val targetComment = withContext(Dispatchers.IO) {
            CommentsAPI.getSingleComment(commentId)
        }
        if (targetComment == null) {
            loadAndDisplayComments()
            return
        }

        val commentChain = mutableListOf<Comment>()
        commentChain.add(targetComment)

        var currentParentId = targetComment.parentCommentId
        var hops = 0
        while (currentParentId != null && currentParentId > 0 && hops < 10) {
            val parent = withContext(Dispatchers.IO) {
                CommentsAPI.getSingleComment(currentParentId!!)
            } ?: break
            commentChain.add(parent)
            currentParentId = parent.parentCommentId
            hops++
        }

        val threadList = commentChain.reversed()

        withContext(Dispatchers.Main) {
            val markwon = buildMarkwon(activity, fragment = this@CommentsFragment)
            var currentParentItem: CommentItem? = null

            threadList.forEachIndexed { index, comment ->
                if (index == 0) {
                    val rootItem = CommentItem(
                        comment,
                        markwon,
                        section,
                        this@CommentsFragment,
                        backgroundColor,
                        0
                    )
                    if (threadList.size > 1) {
                        rootItem.updateRepliesVisibility(true)
                    }
                    section.add(rootItem)
                    currentParentItem = rootItem
                } else {
                    val parent = currentParentItem!!
                    val depth =
                        if (parent.commentDepth + 1 > parent.MAX_DEPTH) parent.commentDepth else parent.commentDepth + 1
                    val targetSection =
                        if (parent.commentDepth + 1 > parent.MAX_DEPTH) parent.parentSection else parent.repliesSection
                    if (depth >= parent.MAX_DEPTH) {
                        parent.registerSubComment(comment.commentId)
                    }
                    val childItem = CommentItem(
                        comment,
                        markwon,
                        targetSection,
                        this@CommentsFragment,
                        backgroundColor,
                        depth
                    )
                    if (index < threadList.size - 1) {
                        childItem.updateRepliesVisibility(true)
                    }
                    targetSection.add(childItem)
                    currentParentItem = childItem
                }
            }

            if (threadList.size == 1 && (targetComment.replyCount ?: 0) > 0) {
                currentParentItem?.showReplies()
            }
        }

        binding.commentsProgressBar.visibility = View.GONE
        binding.commentsList.visibility = View.VISIBLE
    }

    private fun sortComments(comments: List<Comment>?): List<Comment> {
        if (comments == null) return emptyList()
        return when (PrefManager.getVal(PrefName.CommentSortOrder, "newest")) {
            "newest" -> comments.sortedByDescending { CommentItem.timestampToMillis(it.timestamp) }
            "oldest" -> comments.sortedBy { CommentItem.timestampToMillis(it.timestamp) }
            "highest_rated" -> comments.sortedByDescending { it.upvotes - it.downvotes }
            "lowest_rated" -> comments.sortedBy { it.upvotes - it.downvotes }
            else -> comments
        }
    }

    private fun resetOldState(): InteractionState {
        val oldState = interactionState
        interactionState = InteractionState.NONE
        return when (oldState) {
            InteractionState.EDIT -> {
                activity.binding.commentReplyToContainer.visibility = View.GONE
                activity.binding.commentInput.setText("")
                val imm = activity.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(activity.binding.commentInput.windowToken, 0)
                commentWithInteraction?.editing(false)
                InteractionState.EDIT
            }

            InteractionState.REPLY -> {
                activity.binding.commentReplyToContainer.visibility = View.GONE
                activity.binding.commentInput.setText("")
                val imm = activity.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(activity.binding.commentInput.windowToken, 0)
                commentWithInteraction?.replying(false)
                InteractionState.REPLY
            }

            else -> {
                InteractionState.NONE
            }
        }
    }

    fun editCallback(comment: CommentItem) {
        if (resetOldState() == InteractionState.EDIT) return
        commentWithInteraction = comment
        activity.binding.commentInput.setText(comment.comment.content)
        activity.binding.commentInput.requestFocus()
        activity.binding.commentInput.setSelection(activity.binding.commentInput.text.length)
        val imm = activity.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(activity.binding.commentInput, InputMethodManager.SHOW_IMPLICIT)
        interactionState = InteractionState.EDIT
        activity.binding.commentReplyTo.text = "Editing comment"
        activity.binding.commentReplyQuote.text = quoteOf(comment.comment.content)
        activity.binding.commentReplyToCancel.setOnClickListener { resetOldState() }
        activity.binding.commentReplyToContainer.visibility = View.VISIBLE
    }

    fun replyCallback(comment: CommentItem) {
        if (resetOldState() == InteractionState.REPLY) return
        commentWithInteraction = comment
        activity.binding.commentReplyToContainer.visibility = View.VISIBLE
        activity.binding.commentInput.requestFocus()
        activity.binding.commentInput.setSelection(activity.binding.commentInput.text.length)
        val imm = activity.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(activity.binding.commentInput, InputMethodManager.SHOW_IMPLICIT)
        interactionState = InteractionState.REPLY

    }

    fun replyTo(comment: CommentItem, username: String) {
        if (comment.isReplying) {
            activity.binding.commentReplyTo.text = getString(R.string.replying_to, username)
            activity.binding.commentReplyQuote.text = quoteOf(comment.comment.content)
            activity.binding.commentReplyToContainer.visibility = View.VISIBLE
            activity.binding.commentReplyToCancel.setOnClickListener {
                comment.replying(false)
                replyCallback(comment)
                activity.binding.commentReplyToContainer.visibility = View.GONE
            }
        } else {
            activity.binding.commentReplyToContainer.visibility = View.GONE
        }
    }

    private fun quoteOf(content: String): String {
        if (content.contains("||")) return "Spoiler"
        return content.replace(Regex("""!\[[^\]]*]\([^)]*\)"""), "GIF")
            .replace('\n', ' ')
            .trim()
    }

    fun viewReplyCallback(comment: CommentItem) {
        lifecycleScope.launch {
            val replies = withContext(Dispatchers.IO) {
                CommentsAPI.getRepliesFromId(comment.comment.commentId)
            }

            replies?.comments?.forEach {
                val depth =
                    if (comment.commentDepth + 1 > comment.MAX_DEPTH) comment.commentDepth else comment.commentDepth + 1
                val section =
                    if (comment.commentDepth + 1 > comment.MAX_DEPTH) comment.parentSection else comment.repliesSection
                if (depth >= comment.MAX_DEPTH) comment.registerSubComment(it.commentId)
                val newCommentItem = CommentItem(
                    it,
                    buildMarkwon(activity, fragment = this@CommentsFragment),
                    section,
                    this@CommentsFragment,
                    backgroundColor,
                    depth
                )

                section.add(newCommentItem)
            }
        }
    }

    private fun showCommentRulesDialog() {
        activity.customAlertDialog().apply {
            setTitle("Commenting Rules")
                .setMessage(
                    "🚨 BREAK ANY RULE = YOU'RE GONE\n\n" +
                            "1. NO RACISM, DISCRIMINATION, OR HATE SPEECH\n" +
                            "2. NO SPAMMING OR SELF-PROMOTION\n" +
                            "3. ABSOLUTELY NO NSFW CONTENT\n" +
                            "4. ENGLISH ONLY – NO EXCEPTIONS\n" +
                            "5. NO IMPERSONATION, HARASSMENT, OR ABUSE\n" +
                            "6. NO ILLEGAL CONTENT OR EXTREME DISRESPECT TOWARDS ANY FANDOM\n" +
                            "7. DO NOT REQUEST OR SHARE REPOSITORIES/EXTENSIONS\n" +
                            "8. SPOILERS ALLOWED ONLY WITH SPOILER TAGS AND A WARNING\n" +
                            "9. NO SEXUALIZING OR INAPPROPRIATE COMMENTS ABOUT MINOR CHARACTERS\n" +
                            "10. IF IT'S WRONG, DON'T POST IT!\n\n"
                )
            setPosButton("I Understand") {
                PrefManager.setVal(PrefName.FirstComment, false)
                showTagDialogThenProcess()
            }
            setNegButton(R.string.cancel)
            show()
        }
    }

    private fun processComment() {
        var commentText = activity.binding.commentInput.text.toString()
        if (commentText.isEmpty()) {
            snackString("Comment cannot be empty")
            return
        }

        if (isSpoilerMode) {
            commentText = "||$commentText||"
            isSpoilerMode = false
            activity.binding.commentSpoiler.alpha = 0.5f
            activity.binding.commentSpoiler.setImageResource(R.drawable.ic_round_remove_red_eye_24)
        }

        activity.binding.commentInput.text.clear()
        val finalText = commentText
        lifecycleScope.launch {
            if (interactionState == InteractionState.EDIT) {
                handleEditComment(finalText)
            } else {
                handleNewComment(finalText)
                tag = null
                activity.binding.commentLabel.background = ResourcesCompat.getDrawable(
                    resources,
                    R.drawable.ic_label_off_24,
                    null
                )
            }
            resetOldState()
        }
    }

    private suspend fun handleEditComment(commentText: String) {
        val success = withContext(Dispatchers.IO) {
            CommentsAPI.editComment(
                commentWithInteraction?.comment?.commentId ?: return@withContext false, commentText
            )
        }
        if (success) {
            updateCommentInSection(commentText)
        }
    }

    private fun updateCommentInSection(commentText: String) {
        val groups = section.groups
        groups.forEach { item ->
            if (item is CommentItem && item.comment.commentId == commentWithInteraction?.comment?.commentId) {
                updateCommentItem(item, commentText)
                snackString("Comment edited")
            }
        }
    }

    private fun updateCommentItem(item: CommentItem, commentText: String) {
        item.comment.content = commentText
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        item.comment.timestamp = dateFormat.format(System.currentTimeMillis())
        item.highlightOnBind = true
        item.notifyChanged()
    }

    private suspend fun handleNewComment(commentText: String) {
        val success = withContext(Dispatchers.IO) {
            CommentsAPI.comment(
                mediaId,
                if (interactionState == InteractionState.REPLY) commentWithInteraction?.comment?.commentId else null,
                commentText,
                tag
            )
        }
        success?.let {
            hideEmptyState()
            if (interactionState == InteractionState.REPLY) {
                if (commentWithInteraction == null) return@let
                val section =
                    if (commentWithInteraction!!.commentDepth + 1 > commentWithInteraction!!.MAX_DEPTH) commentWithInteraction?.parentSection else commentWithInteraction?.repliesSection
                val depth =
                    if (commentWithInteraction!!.commentDepth + 1 > commentWithInteraction!!.MAX_DEPTH) commentWithInteraction!!.commentDepth else commentWithInteraction!!.commentDepth + 1
                if (depth >= commentWithInteraction!!.MAX_DEPTH) commentWithInteraction!!.registerSubComment(
                    it.commentId
                )
                section?.add(
                    if (commentWithInteraction!!.commentDepth + 1 > commentWithInteraction!!.MAX_DEPTH) 0 else section.itemCount,
                    CommentItem(
                        it,
                        buildMarkwon(activity, fragment = this@CommentsFragment),
                        section,
                        this@CommentsFragment,
                        backgroundColor,
                        depth
                    ).apply { highlightOnBind = true }
                )
            } else {
                section.add(
                    0,
                    CommentItem(
                        it,
                        buildMarkwon(activity, fragment = this@CommentsFragment),
                        section,
                        this@CommentsFragment,
                        backgroundColor,
                        0
                    ).apply { highlightOnBind = true }
                )
            }
        }
    }
}
