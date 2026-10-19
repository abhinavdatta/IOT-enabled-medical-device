package com.waterbench.controller

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages the Triveni appearance themes (Glass / Light / OLED).
 *
 * The selected theme is persisted in SharedPreferences so it survives
 * process death. Call [apply] before setContentView in every activity
 * that needs theming, then read [current] to render theme-specific UI.
 */
object ThemeManager {

    private const val PREF_NAME = "waterbench_theme"
    private const val KEY_THEME = "selected_theme"

    const val THEME_GLASS = "Glass"
    const val THEME_LIGHT = "Light"
    const val THEME_OLED = "OLED"

    private val allThemes = setOf(THEME_GLASS, THEME_LIGHT, THEME_OLED)

    /**
     * Returns the currently selected theme name (defaults to Glass).
     */
    fun current(context: Context): String {
        val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val theme = prefs.getString(KEY_THEME, THEME_GLASS)
        return theme?.takeIf { it in allThemes } ?: THEME_GLASS
    }

    /**
     * Persists the selected theme. The activity applies it before inflating
     * views when it is recreated.
     */
    fun apply(context: Context, theme: String) {
        val t = if (theme in allThemes) theme else THEME_GLASS
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, t)
            .apply()

    }

    /** Applies the previously persisted theme to the activity before setContentView. */
    fun applySaved(context: Context) {
        context.setTheme(themeResId(current(context)))
    }

    /** Maps a theme name to its style resource id. */
    fun themeResId(theme: String): Int = when (theme) {
        THEME_LIGHT -> R.style.Theme_Triveni_Light
        THEME_OLED -> R.style.Theme_Triveni_OLED
        else -> R.style.Theme_Triveni
    }

    /** Returns true if OLED (true-black) theme is active — useful for disabling blur. */
    fun isOled(context: Context): Boolean = current(context) == THEME_OLED

    /** Returns true if Light theme is active. */
    fun isLight(context: Context): Boolean = current(context) == THEME_LIGHT
}
