package com.bithead.shelter.i18n

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import com.bithead.shelter.R
import java.util.Locale

/**
 * In-app language choice. English and Hindi have fixed-string resources;
 * other chosen locales currently retain the English safety-text fallback.
 * Changing the choice recreates the activity.
 */
object AppLanguage {
    private const val PREFS = "shelter_prefs"
    private const val KEY = "app_language"
    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val HINDI = "hi"
    const val BENGALI = "bn"
    const val MARATHI = "mr"
    const val TAMIL = "ta"

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    fun set(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
    }

    /** Returns [base] configured for the chosen language and installs it for [str]. */
    fun wrap(base: Context): Context {
        val tag = get(base)
        val localized = if (tag == SYSTEM) base else {
            val locale = Locale.forLanguageTag(tag)
            val config = Configuration(base.resources.configuration).apply {
                setLocale(locale)
                setLayoutDirection(locale)
            }
            base.createConfigurationContext(config)
        }
        L10n.install(localized)
        return localized
    }

    /** The language actually selected for resources. */
    fun isHindi(context: Context): Boolean =
        context.resources.configuration.locales[0].language == HINDI
}

/** Localized strings reachable from composables, callbacks and services alike. */
object L10n {
    @Volatile
    private var context: Context? = null

    fun install(localized: Context) {
        context = localized
    }

    fun str(@StringRes id: Int, vararg args: Any): String {
        val localized = checkNotNull(context) { "AppLanguage.wrap() must run before strings are read" }
        return if (args.isEmpty()) localized.getString(id) else localized.getString(id, *args)
    }
}

fun str(@StringRes id: Int, vararg args: Any): String = L10n.str(id, *args)

/** Picks the singular or counted plural resource. */
fun plural(count: Int, @StringRes one: Int, @StringRes other: Int): String =
    if (count == 1) str(one) else str(other, count)

/** Localized name of a stored media type code (AUDIO, IMAGE, VIDEO). */
fun mediaTypeLabel(type: String): String = when (type.uppercase(Locale.ROOT)) {
    "VIDEO" -> str(R.string.media_video)
    "IMAGE" -> str(R.string.media_image)
    else -> str(R.string.media_audio)
}
