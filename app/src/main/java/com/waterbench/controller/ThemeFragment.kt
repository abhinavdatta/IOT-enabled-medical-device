package com.waterbench.controller

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Device theme page (PDF page B).
 * Dedicated appearance page: pick Glass / Light / OLED, review live preview,
 * tap Apply theme. Back arrow prompts when edits are unsaved.
 */
class ThemeFragment : Fragment() {

    private lateinit var rgTheme: RadioGroup
    private lateinit var rbGlass: RadioButton
    private lateinit var rbLight: RadioButton
    private lateinit var rbOled: RadioButton
    private lateinit var btnApplyTheme: Button
    private lateinit var tvThemePreview: TextView

    /** Theme selected by the user but not yet applied/persisted. */
    private var pendingTheme: String? = null
    private var hasUnsavedChanges: Boolean = false
        private set

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.screen_theme, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        rgTheme = view.findViewById(R.id.rgTheme)
        rbGlass = view.findViewById(R.id.rbThemeGlass)
        rbLight = view.findViewById(R.id.rbThemeLight)
        rbOled = view.findViewById(R.id.rbThemeOled)
        btnApplyTheme = view.findViewById(R.id.btnApplyTheme)
        tvThemePreview = view.findViewById(R.id.tvThemePreview)
        val btnBackTheme = view.findViewById<ImageButton>(R.id.btnBackTheme)

        // Pre-select the currently persisted theme.
        val current = ThemeManager.current(requireContext())
        when (current) {
            ThemeManager.THEME_LIGHT -> rbLight.isChecked = true
            ThemeManager.THEME_OLED -> rbOled.isChecked = true
            else -> rbGlass.isChecked = true
        }
        pendingTheme = current
        refreshPreview(current)

        rgTheme.setOnCheckedChangeListener { _, checkedId ->
            val selected = when (checkedId) {
                R.id.rbThemeLight -> ThemeManager.THEME_LIGHT
                R.id.rbThemeOled -> ThemeManager.THEME_OLED
                else -> ThemeManager.THEME_GLASS
            }
            pendingTheme = selected
            hasUnsavedChanges = selected != ThemeManager.current(requireContext())
            btnApplyTheme.isEnabled = hasUnsavedChanges
            refreshPreview(selected)
        }

        btnApplyTheme.setOnClickListener {
            val theme = pendingTheme ?: ThemeManager.current(requireContext())
            ThemeManager.apply(requireContext(), theme)
            hasUnsavedChanges = false
            btnApplyTheme.isEnabled = false
            tvThemePreview.text = "Theme applied: $theme"
            // Recreate the activity so all screens redraw with the new theme.
            requireActivity().recreate()
        }

        // Back button in the header bar.
        btnBackTheme.setOnClickListener {
            if (hasUnsavedChanges) {
                confirmLeave()
            } else {
                requireActivity().onBackPressed()
            }
        }

        // Also prompt on system back when unsaved.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (hasUnsavedChanges) {
                        confirmLeave()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    private fun confirmLeave() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.dialog_unsaved_title))
            .setMessage(getString(R.string.dialog_unsaved_msg))
            .setPositiveButton(getString(R.string.dialog_leave)) { _, _ ->
                hasUnsavedChanges = false
                requireActivity().onBackPressed()
            }
            .setNegativeButton(getString(R.string.dialog_keep_editing), null)
            .show()
    }

    private fun refreshPreview(theme: String) {
        tvThemePreview.text = when (theme) {
            ThemeManager.THEME_LIGHT -> "Light theme — bright surfaces, dark text."
            ThemeManager.THEME_OLED -> "OLED theme — true black, accent pop."
            else -> "Glass theme — translucent mint-on-dark surfaces."
        }
    }

    companion object {
        fun newInstance() = ThemeFragment()
    }
}
