package com.bithead.shelter.security

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

object AppDisguiseManager {
    private const val PREFS = "shelter_prefs"
    private const val DISGUISE_ENABLED = "notes_disguise_enabled"
    private const val UNLOCK_SALT = "notes_unlock_salt"
    private const val UNLOCK_HASH = "notes_unlock_hash"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(DISGUISE_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        applyLauncherState(context, enabled)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(DISGUISE_ENABLED, enabled)
            .apply()
    }

    /** True once the user has chosen a secret code for the Notes search box. */
    fun hasUnlockCode(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(UNLOCK_HASH)

    /**
     * Stores only a salted SHA-256 of the code, never the code itself. Codes
     * are compared case-insensitively so a phone keyboard's auto-capital works.
     */
    fun setUnlockCode(context: Context, code: String) {
        val normalized = normalizeCode(code)
        require(normalized.length >= MIN_CODE_LENGTH) { "Use at least $MIN_CODE_LENGTH characters" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(UNLOCK_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(UNLOCK_HASH, Base64.encodeToString(hashUnlockCode(salt, normalized), Base64.NO_WRAP))
            .apply()
    }

    fun clearUnlockCode(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(UNLOCK_SALT).remove(UNLOCK_HASH).apply()
    }

    /** Constant-time check of text typed into the Notes search box. */
    fun matchesUnlockCode(context: Context, input: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val salt = prefs.getString(UNLOCK_SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val expected = prefs.getString(UNLOCK_HASH, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val normalized = normalizeCode(input)
        if (normalized.length < MIN_CODE_LENGTH) return false
        return MessageDigest.isEqual(expected, hashUnlockCode(salt, normalized))
    }

    internal const val MIN_CODE_LENGTH = 4

    internal fun normalizeCode(code: String): String = code.trim().lowercase(Locale.ROOT)

    internal fun hashUnlockCode(salt: ByteArray, normalizedCode: String): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update(salt)
            digest(normalizedCode.toByteArray(Charsets.UTF_8))
        }

    fun applyLauncherState(context: Context, enabled: Boolean) {
        val packageManager = context.packageManager
        val vaaniAlias = ComponentName(context, "${context.packageName}.VaaniAlias")
        val notesAlias = ComponentName(context, "${context.packageName}.NotesAlias")
        val activeAlias = if (enabled) notesAlias else vaaniAlias
        val inactiveAlias = if (enabled) vaaniAlias else notesAlias

        packageManager.setComponentEnabledSetting(
            activeAlias,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        packageManager.setComponentEnabledSetting(
            inactiveAlias,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}
