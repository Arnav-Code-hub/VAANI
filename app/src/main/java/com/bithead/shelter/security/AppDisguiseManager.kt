package com.bithead.shelter.security

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object AppDisguiseManager {
    private const val PREFS = "shelter_prefs"
    private const val DISGUISE_ENABLED = "notes_disguise_enabled"

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
