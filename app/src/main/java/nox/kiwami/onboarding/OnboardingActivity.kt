package nox.kiwami.onboarding

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.widget.GridLayout
import androidx.documentfile.provider.DocumentFile
import nox.kiwami.databinding.ItemOnboardingPermissionBinding
import nox.kiwami.settings.saving.internal.PreferencePackager
import nox.kiwami.toast
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import nox.kiwami.MainActivity
import nox.kiwami.R
import nox.kiwami.databinding.ActivityOnboardingBinding
import nox.kiwami.databinding.ItemOnboardingPageBinding
import nox.kiwami.hasNotificationPermission
import nox.kiwami.notifications.airing.AiringAlarmScheduler
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import nox.kiwami.themes.ThemeManager
import nox.kiwami.util.LauncherWrapper
import nox.kiwami.util.StoragePermissions
import kotlin.math.abs

class OnboardingActivity : AppCompatActivity() {

    private enum class Kind { INFO, RESTORE, DISCLAIMER, PERMISSIONS, THEMES, DONE }

    private enum class Perm(
        @DrawableRes val icon: Int,
        @StringRes val title: Int,
        @StringRes val desc: Int,
        @StringRes val action: Int
    ) {
        NOTIFICATIONS(
            R.drawable.ic_round_notifications_active_24, R.string.onboarding_perm_notifications,
            R.string.onboarding_perm_notifications_desc, R.string.onboarding_perm_allow
        ),
        BATTERY(
            R.drawable.ic_onboarding_battery, R.string.onboarding_perm_battery,
            R.string.onboarding_perm_battery_desc, R.string.onboarding_perm_allow
        ),
        ALARMS(
            R.drawable.ic_onboarding_alarm, R.string.onboarding_perm_alarms,
            R.string.onboarding_perm_alarms_desc, R.string.onboarding_perm_allow
        ),
        STORAGE(
            R.drawable.ic_round_folder_24, R.string.onboarding_perm_storage,
            R.string.onboarding_perm_storage_desc, R.string.onboarding_perm_choose
        );

        fun isRelevant() = this != ALARMS || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }

    private enum class Visual { NONE, MARK, TRACK }

    private data class Page(
        val kind: Kind,
        val visual: Visual,
        val split: Float,
        val tilt: Float,
        @StringRes val eyebrow: Int,
        @StringRes val title: Int,
        @StringRes val description: Int
    )

    private val pages = listOf(
        Page(
            Kind.INFO, Visual.MARK, 0.58f, -16f, R.string.onboarding_eyebrow_welcome,
            R.string.onboarding_welcome_title, R.string.onboarding_welcome_desc
        ),
        Page(
            Kind.INFO, Visual.TRACK, 0.58f, -12f, R.string.onboarding_eyebrow_track,
            R.string.onboarding_track_title, R.string.onboarding_track_desc
        ),
        Page(
            Kind.RESTORE, Visual.NONE, 0.5f, -20f, R.string.onboarding_eyebrow_restore,
            R.string.onboarding_restore_title, R.string.onboarding_restore_desc
        ),
        Page(
            Kind.DISCLAIMER, Visual.NONE, 0.5f, -14f, R.string.onboarding_eyebrow_disclaimer,
            R.string.onboarding_disclaimer_title, R.string.onboarding_disclaimer_desc
        ),
        Page(
            Kind.PERMISSIONS, Visual.NONE, 0.22f, -18f, R.string.onboarding_eyebrow_permissions,
            R.string.onboarding_permissions_title, R.string.onboarding_permissions_desc
        ),
        Page(
            Kind.THEMES, Visual.NONE, 0.38f, -10f, R.string.onboarding_eyebrow_themes,
            R.string.onboarding_themes_title, R.string.onboarding_themes_desc
        ),
        Page(
            Kind.DONE, Visual.MARK, 0.58f, -16f, R.string.onboarding_eyebrow_done,
            R.string.onboarding_done_title, R.string.onboarding_done_desc
        )
    )

    private val disclaimerIndex = pages.indexOfFirst { it.kind == Kind.DISCLAIMER }

    private val themeSwatches = mapOf(
        "BLUE" to R.color.md_theme_dark_1_primary,
        "GREEN" to R.color.md_theme_dark_2_primary,
        "PURPLE" to R.color.md_theme_dark_3_primary,
        "PINK" to R.color.md_theme_dark_4_primary,
        "SAIKOU" to R.color.md_theme_dark_5_primary,
        "RED" to R.color.md_theme_dark_6_primary,
        "LAVENDER" to R.color.md_theme_dark_7_primary,
        "OCEAN" to R.color.md_theme_dark_8_primary,
        "ORIAX" to R.color.md_theme_dark_9_primary
    )

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var folderLauncher: LauncherWrapper
    private val pageAdapter = PageAdapter()
    private val dots = mutableListOf<View>()
    private val scrim = OnboardingScrim()
    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // After two refusals Android silently ignores the request, so fall back to settings.
            if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                openNotificationSettings()
            }
            refreshStatuses()
        }

    private val restoreLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                val bytes = contentResolver.openInputStream(uri)?.readBytes()
                    ?: throw Exception("Error reading file")
                val name = DocumentFile.fromSingleUri(this, uri)?.name ?: ""
                if (!name.endsWith(".ani")) {
                    toast(getString(R.string.unknown_file_type))
                    return@registerForActivityResult
                }
                if (PreferencePackager.unpack(bytes.toString(Charsets.UTF_8))) {
                    PrefManager.setVal(PrefName.OnboardingDone, true)
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                    finish()
                } else {
                    toast(getString(R.string.error_importing_settings))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                toast(getString(R.string.error_importing_settings))
            }
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        folderLauncher = LauncherWrapper(this, ActivityResultContracts.OpenDocumentTree())

        binding.scrim.background = scrim

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.skipButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = bars.top + dp(8)
            }
            binding.bottomBar.updatePadding(bottom = bars.bottom + dp(20))
            insets
        }

        setupPager()
        setupDots()
        setupButtons()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val pager = binding.onboardingPager
                if (pager.currentItem > 0) {
                    pager.currentItem = pager.currentItem - 1
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        binding.tileWall.start()
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
    }

    override fun onStop() {
        super.onStop()
        binding.tileWall.stop()
    }

    private fun setupPager() {
        val pager = binding.onboardingPager
        pager.adapter = pageAdapter
        pager.offscreenPageLimit = 1

        pager.setPageTransformer { page, position ->
            val width = page.width.toFloat()
            page.findViewById<View>(R.id.iconContainer)?.translationX = -position * width * 0.35f
            page.findViewById<View>(R.id.pageTitle)?.translationX = position * width * 0.15f
            page.findViewById<View>(R.id.pageDescription)?.translationX = position * width * 0.25f
            page.alpha = 1f - (abs(position) * 0.85f).coerceIn(0f, 1f)
        }

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(
                position: Int, positionOffset: Float, positionOffsetPixels: Int
            ) {
                updateDots(position + positionOffset)
                binding.tileWall.setParallax(-(position + positionOffset) * dp(36))
                val next = pages[(position + 1).coerceAtMost(pages.lastIndex)]
                val current = pages[position]
                scrim.split = current.split + (next.split - current.split) * positionOffset
                binding.tileWall.setTilt(current.tilt + (next.tilt - current.tilt) * positionOffset)
            }

            override fun onPageSelected(position: Int) {
                updateButtons(position)
                playEntrance(position)
            }
        })

        scrim.split = pages[0].split
        binding.tileWall.setTilt(pages[0].tilt)
        updateButtons(0)
        pager.post { playEntrance(0) }
    }

    private fun setupDots() {
        val container = binding.dotsContainer
        pages.indices.forEach { _ ->
            val dot = View(this).apply {
                setBackgroundResource(R.drawable.bg_onboarding_dot)
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                    marginEnd = dp(8)
                }
            }
            container.addView(dot)
            dots.add(dot)
        }
        updateDots(0f)
    }

    private fun updateDots(position: Float) {
        dots.forEachIndexed { index, dot ->
            val fraction = (1f - abs(position - index)).coerceIn(0f, 1f)
            dot.alpha = 0.4f + 0.6f * fraction
            val width = dp(8) + (dp(20) * fraction).toInt()
            if (dot.layoutParams.width != width) {
                dot.layoutParams = dot.layoutParams.apply { this.width = width }
            }
        }
    }

    private fun setupButtons() {
        binding.nextButton.setOnClickListener {
            val pager = binding.onboardingPager
            if (pager.currentItem >= pages.lastIndex) finishOnboarding()
            else pager.setCurrentItem(pager.currentItem + 1, true)
        }
        binding.skipButton.setOnClickListener {
            binding.onboardingPager.setCurrentItem(disclaimerIndex, true)
        }
    }

    private fun updateButtons(position: Int) {
        val page = pages[position]
        val last = position == pages.lastIndex
        val showSkip = position < disclaimerIndex
        binding.skipButton.animate().alpha(if (showSkip) 1f else 0f).setDuration(200).start()
        binding.skipButton.isEnabled = showSkip
        binding.nextButton.setText(
            when {
                last -> R.string.onboarding_get_started
                page.kind == Kind.RESTORE -> R.string.onboarding_start_fresh
                page.kind == Kind.PERMISSIONS && !allPermsGranted() -> R.string.onboarding_maybe_later
                else -> R.string.onboarding_next
            }
        )
    }

    private fun relevantPerms() = Perm.entries.filter { it.isRelevant() }

    private fun allPermsGranted() = relevantPerms().all { isGranted(it) }

    private fun isGranted(perm: Perm): Boolean = when (perm) {
        Perm.NOTIFICATIONS -> hasNotificationPermission(this)
        Perm.BATTERY -> (getSystemService(POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)

        Perm.ALARMS -> AiringAlarmScheduler.canScheduleExact(this)
        Perm.STORAGE -> StoragePermissions.hasDirAccess(this)
    }

    private fun performAction(perm: Perm) {
        when (perm) {
            Perm.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openNotificationSettings()
                }
            }

            Perm.BATTERY -> {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (e2: Exception) {
                        openAppDetails()
                    }
                }
            }

            Perm.ALARMS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                .setData(Uri.parse("package:$packageName"))
                        )
                    } catch (e: Exception) {
                        openAppDetails()
                    }
                }
            }

            Perm.STORAGE -> {
                folderLauncher.registerForCallback { refreshStatuses() }
                folderLauncher.launch()
            }

        }
    }

    private fun openNotificationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (e: Exception) {
            openAppDetails()
        }
    }

    private fun openAppDetails() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
        }
    }

    private fun refreshStatuses() {
        if (!::binding.isInitialized) return
        pages.forEachIndexed { index, page ->
            if (page.kind == Kind.PERMISSIONS) pageAdapter.notifyItemChanged(index, PAYLOAD_STATUS)
        }
        updateButtons(binding.onboardingPager.currentItem)
    }

    private fun finishOnboarding() {
        PrefManager.setVal(PrefName.OnboardingDone, true)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    private fun currentHolder(position: Int): PageViewHolder? {
        val recycler = binding.onboardingPager.getChildAt(0) as? RecyclerView ?: return null
        return recycler.findViewHolderForAdapterPosition(position) as? PageViewHolder
    }

    private fun playEntrance(position: Int) {
        val holder = currentHolder(position) ?: return
        val b = holder.b
        val page = pages[position]
        listOf<View>(
            b.pageEyebrow, b.pageTitle, b.pageDescription,
            b.noteCard, b.permissionList, b.themeGrid, b.themeName, b.themeHint, b.actionButton
        )
            .forEachIndexed { index, view ->
                if (!view.isVisible) return@forEachIndexed
                view.animate().cancel()
                view.translationY = dp(28).toFloat()
                view.animate()
                    .translationY(0f)
                    .setStartDelay(70L * index)
                    .setDuration(450)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        when (page.visual) {
            Visual.MARK -> b.markView.play()
            Visual.TRACK -> holder.playTrackCard()
            Visual.NONE -> Unit
        }
    }

    private inner class PageViewHolder(val b: ItemOnboardingPageBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(page: Page) {
            b.splitGuide.setGuidelinePercent(page.split)
            b.markView.isVisible = page.visual == Visual.MARK
            b.markView.reset()
            b.trackCard.isVisible = page.visual == Visual.TRACK
            b.pageEyebrow.setText(page.eyebrow)
            b.pageTitle.text = accentTitle(page.title)
            b.pageDescription.setText(page.description)

            b.noteCard.isVisible = page.kind == Kind.DISCLAIMER
            b.noteCard.setText(R.string.onboarding_disclaimer_note)
            b.permissionList.isVisible = page.kind == Kind.PERMISSIONS
            b.themeGrid.isVisible = page.kind == Kind.THEMES
            b.themeName.isVisible = page.kind == Kind.THEMES
            b.themeHint.isVisible = page.kind == Kind.THEMES
            b.statusChip.isVisible = false

            when (page.kind) {
                Kind.RESTORE -> {
                    b.actionButton.isVisible = true
                    b.actionButton.setText(R.string.onboarding_restore_action)
                    b.actionButton.setOnClickListener { restoreLauncher.launch(arrayOf("*/*")) }
                }

                Kind.PERMISSIONS -> {
                    b.actionButton.isVisible = false
                    buildPermissionRows()
                }

                Kind.THEMES -> {
                    b.actionButton.isVisible = false
                    buildThemeGrid()
                }

                else -> b.actionButton.isVisible = false
            }
        }

        fun playTrackCard() {
            b.trackCard.animate().cancel()
            b.trackCard.alpha = 0f
            b.trackCard.translationY = dp(36).toFloat()
            b.trackCard.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(120)
                .setDuration(550)
                .setInterpolator(DecelerateInterpolator())
                .start()
            b.trackFill.animate().cancel()
            b.trackFill.pivotX = 0f
            b.trackFill.scaleX = 0f
            b.trackFill.animate()
                .scaleX(1f)
                .setStartDelay(500)
                .setDuration(900)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .start()
        }

        fun stopAnimations() {
            b.markView.reset()
            b.trackCard.animate().cancel()
            b.trackCard.alpha = 0f
            b.trackFill.animate().cancel()
            b.trackFill.scaleX = 0f
        }

        private fun accentTitle(@StringRes res: Int): CharSequence {
            val text = SpannableStringBuilder(getText(res))
            val accent = getColor(R.color.onb_accent)
            text.getSpans(0, text.length, StyleSpan::class.java).forEach { span ->
                val start = text.getSpanStart(span)
                val end = text.getSpanEnd(span)
                text.removeSpan(span)
                text.setSpan(ForegroundColorSpan(accent), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return text
        }

        private fun buildPermissionRows() {
            b.permissionList.removeAllViews()
            relevantPerms().forEach { perm ->
                val row = ItemOnboardingPermissionBinding.inflate(
                    LayoutInflater.from(b.root.context), b.permissionList, false
                )
                row.permIcon.setImageResource(perm.icon)
                row.permTitle.setText(perm.title)
                row.permDesc.setText(perm.desc)
                row.permButton.setText(perm.action)
                row.permButton.setOnClickListener { performAction(perm) }
                row.root.tag = perm
                b.permissionList.addView(row.root)
            }
            refreshPermissionRows()
        }

        fun bindStatus(page: Page) {
            if (page.kind == Kind.PERMISSIONS) refreshPermissionRows()
        }

        private fun refreshPermissionRows() {
            for (i in 0 until b.permissionList.childCount) {
                val row = b.permissionList.getChildAt(i)
                val perm = row.tag as? Perm ?: continue
                val granted = isGranted(perm)
                row.findViewById<View>(R.id.permButton).isVisible = !granted
                row.findViewById<View>(R.id.permCheck).isVisible = granted
            }
        }

        private fun buildThemeGrid() {
            val grid = b.themeGrid
            grid.removeAllViews()
            val current: String = PrefManager.getVal(PrefName.Theme)
            val entries = themeSwatches.keys.toList() + "MONOCHROME (BETA)"
            val circles = mutableMapOf<String, View>()

            fun style(key: String, selected: Boolean) {
                val color = themeSwatches[key]?.let { getColor(it) } ?: 0xFF9E9E9E.toInt()
                circles[key]?.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(
                        dp(if (selected) 4 else 1),
                        if (selected) Color.WHITE else 0x66FFFFFF
                    )
                }
                circles[key]?.animate()?.scaleX(if (selected) 1.15f else 1f)
                    ?.scaleY(if (selected) 1.15f else 1f)?.setDuration(180)?.start()
            }

            fun label(key: String) = key.substring(0, 1) + key.substring(1).lowercase()

            entries.forEach { key ->
                val circle = View(b.root.context).apply {
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = dp(48); height = dp(48)
                        setMargins(dp(6), dp(6), dp(6), dp(6))
                        setGravity(Gravity.CENTER)
                    }
                    contentDescription = label(key)
                    setOnClickListener {
                        PrefManager.setVal(PrefName.Theme, key)
                        PrefManager.setVal(PrefName.UseMaterialYou, false)
                        PrefManager.setVal(PrefName.UseCustomTheme, false)
                        entries.forEach { style(it, it == key) }
                        b.themeName.text = label(key)
                    }
                }
                circles[key] = circle
                grid.addView(circle)
                style(key, key == current)
            }
            b.themeName.text = label(if (current in themeSwatches) current else "BLUE")
        }
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageViewHolder>() {
        override fun getItemCount() = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PageViewHolder(
            ItemOnboardingPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            holder.bind(pages[position])
        }

        override fun onBindViewHolder(
            holder: PageViewHolder, position: Int, payloads: MutableList<Any>
        ) {
            if (payloads.contains(PAYLOAD_STATUS)) holder.bindStatus(pages[position])
            else super.onBindViewHolder(holder, position, payloads)
        }

        override fun onViewAttachedToWindow(holder: PageViewHolder) {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION && position == binding.onboardingPager.currentItem) {
                playEntrance(position)
            }
        }

        override fun onViewDetachedFromWindow(holder: PageViewHolder) {
            holder.stopAnimations()
        }
    }

    companion object {
        private const val PAYLOAD_STATUS = "status"
    }
}
