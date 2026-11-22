package nox.kiwami.media

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import nox.kiwami.BottomSheetDialogFragment
import nox.kiwami.R
import nox.kiwami.databinding.SheetSourceSettingsBinding
import nox.kiwami.navBarHeight

class SourceSettingsSheet : BottomSheetDialogFragment() {

    private var _binding: SheetSourceSettingsBinding? = null
    private val binding get() = _binding!!

    var title: String = ""
    var subtitle: String = ""
    var factory: (() -> PreferenceFragmentCompat)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        if (dialog is BottomSheetDialog) {
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetSourceSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val make = factory ?: run {
            dismissAllowingStateLoss()
            return
        }
        binding.root.updatePadding(bottom = navBarHeight)
        binding.sourceSettingsTitle.text = title
        binding.sourceSettingsSubtitle.text = subtitle
        binding.sourceSettingsContainer.updateLayoutParams {
            height = (resources.displayMetrics.heightPixels * 0.6f).toInt()
        }
        SheetMotion.popIn(binding.sourceSettingsTitle, 0)
        SheetMotion.popIn(binding.sourceSettingsSubtitle, 1)

        childFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: FragmentManager,
                    f: Fragment,
                    v: View,
                    savedInstanceState: Bundle?
                ) {
                    val prefs = f as? PreferenceFragmentCompat ?: return
                    v.setBackgroundColor(Color.TRANSPARENT)
                    SheetMotion.popInRows(prefs.listView, 10, 2)
                }
            },
            false
        )
        if (childFragmentManager.findFragmentById(R.id.sourceSettingsContainer) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.sourceSettingsContainer, make())
                .commit()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.background = SourcePicker.sheetBackground(requireContext())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun show(
            manager: FragmentManager,
            title: String,
            subtitle: String,
            factory: () -> PreferenceFragmentCompat
        ) {
            SourceSettingsSheet().apply {
                this.title = title
                this.subtitle = subtitle
                this.factory = factory
            }.show(manager, "SourceSettingsSheet")
        }
    }
}
