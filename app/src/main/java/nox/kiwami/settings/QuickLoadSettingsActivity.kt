package nox.kiwami.settings

import android.os.Bundle
import android.text.format.Formatter
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nox.kiwami.R
import nox.kiwami.databinding.ActivitySettingsQuickLoadBinding
import nox.kiwami.initActivity
import nox.kiwami.navBarHeight
import nox.kiwami.others.PinnedImages
import nox.kiwami.others.SmartCache
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import nox.kiwami.statusBarHeight
import nox.kiwami.themes.ThemeManager
import nox.kiwami.toast
import nox.kiwami.util.customAlertDialog

class QuickLoadSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsQuickLoadBinding

    private var usage = ""
    private var masterItem: Settings? = null
    private var suppressMasterSwitch = false
    private val subItems = ArrayList<Settings>()

    private val homeRowNames = arrayOf(
        "Continue Watching",
        "Continue Reading",
        "Planned Anime",
        "Planned Manga",
        "Favourite Anime",
        "Favourite Manga"
    )
    private val ageHours = intArrayOf(1, 3, 6, 12, 24)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivitySettingsQuickLoadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.quickLoadLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
        binding.quickLoadBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        usage = buildUsage()
        setupRecyclerView()
        refreshUsage()
    }

    private fun sizeLabel(mb: Float): String =
        if (mb >= 1000f) "1 GB" else "${mb.toInt()} MB"

    private fun buildUsage(): String {
        if (!SmartCache.isEnabled()) return "Off. Nothing is being saved."
        val home = SmartCache.homeBytes(this)
        val info = SmartCache.infoBytes(this)
        val images = SmartCache.imageBytes(this)
        val total = Formatter.formatFileSize(this, home + info + images)
        return "$total used of ${sizeLabel(SmartCache.limitMb().toFloat())}\n" +
            "Home: ${Formatter.formatFileSize(this, home)}\n" +
            "Media details: ${Formatter.formatFileSize(this, info)}\n" +
            "Images: ${Formatter.formatFileSize(this, images)}"
    }

    private fun refreshUsage() {
        lifecycleScope.launch(Dispatchers.IO) {
            val text = buildUsage()
            withContext(Dispatchers.Main) {
                usage = text
                binding.quickLoadRecyclerView.adapter?.notifyDataSetChanged()
            }
        }
    }

    private fun refreshList() {
        val on = SmartCache.isEnabled()
        subItems.forEach { it.isVisible = on }
        masterItem?.isChecked = on
        binding.quickLoadRecyclerView.adapter?.notifyDataSetChanged()
    }

    private fun inBackground(work: () -> Unit, message: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            work()
            withContext(Dispatchers.Main) {
                toast(message)
                refreshUsage()
            }
        }
    }

    private fun toggle(
        name: String,
        desc: String,
        icon: Int,
        pref: PrefName,
        onChange: (Boolean) -> Unit = {}
    ) = Settings(
        type = 2,
        name = name,
        desc = desc,
        icon = icon,
        isChecked = PrefManager.getVal(pref),
        switch = { isChecked, _ ->
            PrefManager.setVal(pref, isChecked)
            onChange(isChecked)
        }
    )

    private fun homeRowsFlags(): BooleanArray {
        val saved = PrefManager.getVal<List<Boolean>>(PrefName.QuickLoadHomeRows)
        return BooleanArray(homeRowNames.size) { saved.getOrElse(it) { true } }
    }

    private fun ageLabel(hours: Int) = if (hours == 1) "1 hour" else "$hours hours"

    private fun resetDefaults() {
        PrefManager.setVal(PrefName.QuickLoadHome, true)
        PrefManager.setVal(PrefName.QuickLoadMedia, true)
        PrefManager.setVal(PrefName.QuickLoadImages, true)
        PrefManager.setVal(PrefName.QuickLoadHomeRows, List(homeRowNames.size) { true })
        PrefManager.setVal(PrefName.QuickLoadKeepLibraryImages, true)
        PrefManager.setVal(PrefName.QuickLoadPinOnWifiOnly, false)
        PrefManager.setVal(PrefName.QuickLoadProtectApi, true)
        PrefManager.setVal(PrefName.QuickLoadMaxAgeHours, 6)
        PrefManager.setVal(PrefName.SmartCacheLimitMb, 500)
        SmartCache.onLimitChanged()
    }

    private fun setupRecyclerView() {
        val context = this

        val master = Settings(
            type = 2,
            name = getString(R.string.quick_load),
            desc = getString(R.string.quick_load_desc),
            icon = R.drawable.ic_round_auto_awesome_24,
            isChecked = SmartCache.isEnabled(),
            switch = { isChecked, view ->
                if (isChecked) {
                    context.customAlertDialog().apply {
                        setTitle("Turn on Quick Load?")
                        setMessage(
                            "The first time, this downloads the covers and banners of your list " +
                                "and your saved Home rows, which can use a good amount of data. " +
                                "Wi-Fi is recommended.\n\nSaved content stays on your device " +
                                "until you turn this off, which deletes it again."
                        )
                        setCancelable(false)
                        setPosButton("Turn on") {
                            PrefManager.setVal(PrefName.SmartCacheEnabled, true)
                            SmartCache.onEnabled()
                            toast("Quick Load is on. The image cache size fully applies after restarting the app.")
                            usage = buildUsage()
                            refreshList()
                        }
                        setNegButton("Cancel") {
                            suppressMasterSwitch = true
                            view.settingsButton.isChecked = false
                            suppressMasterSwitch = false
                        }
                        show()
                    }
                } else if (!suppressMasterSwitch) {
                    PrefManager.setVal(PrefName.SmartCacheEnabled, false)
                    usage = "Deleting…"
                    refreshList()
                    lifecycleScope.launch(Dispatchers.IO) {
                        SmartCache.clearAll(context)
                        withContext(Dispatchers.Main) {
                            toast("Quick Load is off and its saved data was deleted")
                            usage = buildUsage()
                            binding.quickLoadRecyclerView.adapter?.notifyDataSetChanged()
                        }
                    }
                }
            }
        ).also { masterItem = it }

        val usageItem = Settings(
            type = 1,
            name = "Storage used",
            desc = "",
            icon = R.drawable.ic_round_folder_24,
            attach = { b -> b.settingsDesc.text = usage },
            onClick = {}
        )

        val limitItem = Settings(
            type = 1,
            name = "Storage limit",
            desc = "",
            icon = R.drawable.ic_round_folder_24,
            attach = { b ->
                b.settingsDesc.text = "Limit for saved images: " +
                    sizeLabel(SmartCache.limitMb().toFloat())
                b.attachView.removeAllViews()
                val steps = (SmartCache.MAX_MB - SmartCache.MIN_MB) / SmartCache.STEP_MB
                val snapped = SmartCache.MIN_MB + SmartCache.STEP_MB *
                    ((SmartCache.limitMb() - SmartCache.MIN_MB).toFloat() / SmartCache.STEP_MB)
                        .toInt().coerceIn(0, steps)
                val slider = Slider(context).apply {
                    valueFrom = SmartCache.MIN_MB.toFloat()
                    valueTo = SmartCache.MAX_MB.toFloat()
                    stepSize = SmartCache.STEP_MB.toFloat()
                    value = snapped.toFloat()
                    setLabelFormatter { v -> sizeLabel(v) }
                    addOnChangeListener { _, v, fromUser ->
                        if (fromUser) b.settingsDesc.text = "Limit for saved images: " + sizeLabel(v)
                    }
                    addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                        override fun onStartTrackingTouch(slider: Slider) {}
                        override fun onStopTrackingTouch(slider: Slider) {
                            PrefManager.setVal(PrefName.SmartCacheLimitMb, slider.value.toInt())
                            SmartCache.onLimitChanged()
                            toast("Saved. The image cache size fully applies after restarting the app.")
                            refreshUsage()
                        }
                    })
                }
                b.attachView.addView(
                    slider,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            },
            onClick = {}
        )

        val homeSwitch = toggle(
            "Home",
            "Save your Home rows for instant loading",
            R.drawable.ic_round_home_24,
            PrefName.QuickLoadHome
        ) { checked ->
            if (!checked) inBackground({ SmartCache.clearHome() }, "Home cache cleared")
        }

        val homeRows = Settings(
            type = 1,
            name = "Home rows",
            desc = "",
            icon = R.drawable.ic_round_home_24,
            attach = { b ->
                b.settingsDesc.text = "${homeRowsFlags().count { it }} of ${homeRowNames.size} rows saved"
            },
            onClick = {
                val flags = homeRowsFlags()
                context.customAlertDialog().apply {
                    setTitle("Rows to save")
                    multiChoiceItems(homeRowNames, flags) { updated ->
                        PrefManager.setVal(PrefName.QuickLoadHomeRows, updated.toList())
                        PinnedImages.requestSync()
                        binding.quickLoadRecyclerView.adapter?.notifyDataSetChanged()
                    }
                    setPosButton("Done")
                    show()
                }
            }
        )

        val mediaSwitch = toggle(
            "Media details",
            "Save details pages you open",
            R.drawable.ic_round_info_24,
            PrefName.QuickLoadMedia
        ) { checked ->
            if (!checked) inBackground({ SmartCache.clearMedia() }, "Media details cleared")
        }

        val imagesSwitch = toggle(
            "Images",
            "Save covers and banners",
            R.drawable.ic_round_image_search_24,
            PrefName.QuickLoadImages
        ) { checked ->
            if (checked) {
                PinnedImages.requestSync()
            } else {
                inBackground({ SmartCache.clearImages(context) }, "Images cleared")
            }
        }

        val keepLibrary = toggle(
            "Keep my library images",
            "Never re-download covers and banners of your lists",
            R.drawable.ic_round_image_search_24,
            PrefName.QuickLoadKeepLibraryImages
        ) { checked ->
            if (checked) {
                PinnedImages.requestSync()
            } else {
                inBackground({ PinnedImages.clear() }, "Library images cleared")
            }
        }

        val wifiOnly = toggle(
            "Pin images on Wi-Fi only",
            "Skip saving library images on mobile data",
            R.drawable.ic_round_folder_24,
            PrefName.QuickLoadPinOnWifiOnly
        ) { checked -> if (!checked) PinnedImages.requestSync() }

        val protectApi = toggle(
            "Protect API limit",
            "Show saved rows and skip refreshing when requests run low",
            R.drawable.ic_round_info_24,
            PrefName.QuickLoadProtectApi
        )

        val maxAge = Settings(
            type = 1,
            name = "Keep Home rows for",
            desc = "",
            icon = R.drawable.ic_round_history_24,
            attach = { b ->
                b.settingsDesc.text =
                    ageLabel(PrefManager.getVal<Int>(PrefName.QuickLoadMaxAgeHours))
            },
            onClick = {
                val current = PrefManager.getVal<Int>(PrefName.QuickLoadMaxAgeHours)
                context.customAlertDialog().apply {
                    setTitle("Keep Home rows for")
                    singleChoiceItems(
                        ageHours.map { ageLabel(it) }.toTypedArray(),
                        ageHours.indexOf(current)
                    ) { i ->
                        PrefManager.setVal(PrefName.QuickLoadMaxAgeHours, ageHours[i])
                        binding.quickLoadRecyclerView.adapter?.notifyDataSetChanged()
                    }
                    show()
                }
            }
        )

        val clearHome = Settings(
            type = 1,
            name = "Clear Home cache",
            desc = "Delete saved Home rows",
            icon = R.drawable.ic_round_delete_24,
            onClick = { inBackground({ SmartCache.clearHome() }, "Home cache cleared") }
        )

        val clearMedia = Settings(
            type = 1,
            name = "Clear media details",
            desc = "Delete saved details pages",
            icon = R.drawable.ic_round_delete_24,
            onClick = { inBackground({ SmartCache.clearMedia() }, "Media details cleared") }
        )

        val clearImages = Settings(
            type = 1,
            name = "Clear images",
            desc = "Delete saved covers and banners",
            icon = R.drawable.ic_round_delete_24,
            onClick = {
                inBackground({ SmartCache.clearImages(context) }, "Images cleared")
            }
        )

        val clearAll = Settings(
            type = 1,
            name = "Clear all",
            desc = "Delete everything Quick Load has saved",
            icon = R.drawable.ic_round_delete_24,
            onClick = {
                context.customAlertDialog().apply {
                    setTitle("Clear all saved content?")
                    setMessage("Quick Load stays on and will save content again as you browse.")
                    setPosButton("Clear") {
                        inBackground({ SmartCache.clearAll(context) }, "Quick Load data cleared")
                    }
                    setNegButton("Cancel")
                    show()
                }
            }
        )

        val reset = Settings(
            type = 1,
            name = "Reset to defaults",
            desc = "Restore the default Quick Load options",
            icon = R.drawable.ic_round_history_24,
            onClick = {
                context.customAlertDialog().apply {
                    setTitle("Reset Quick Load options?")
                    setMessage("Your saved content is kept. Only the options go back to their defaults.")
                    setPosButton("Reset") {
                        resetDefaults()
                        recreate()
                    }
                    setNegButton("Cancel")
                    show()
                }
            }
        )

        subItems.addAll(
            listOf(
                limitItem, homeSwitch, homeRows, mediaSwitch, imagesSwitch, keepLibrary,
                wifiOnly, protectApi, maxAge, clearHome, clearMedia, clearImages, clearAll, reset
            )
        )
        val on = SmartCache.isEnabled()
        subItems.forEach { it.isVisible = on }

        val list = arrayListOf(master, usageItem)
        list.addAll(subItems)

        binding.quickLoadRecyclerView.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            adapter = SettingsAdapter(list)
            setHasFixedSize(true)
        }
    }
}
