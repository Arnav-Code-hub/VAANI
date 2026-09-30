package com.bithead.shelter.data

import android.content.Context
import android.telephony.PhoneNumberUtils
import com.bithead.shelter.i18n.AppLanguage
import org.json.JSONArray
import org.json.JSONObject

data class TrustedContact(val name: String, val number: String, val smsLanguage: String = AppLanguage.ENGLISH)

object TrustedContacts {
    private const val PREFS = "vaani_trusted_contacts"

    fun load(context: Context): List<TrustedContact> = runCatching {
        val data = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("entries", "[]")
        val array = JSONArray(data)
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            TrustedContact(item.getString("name"), item.getString("number"),
                item.optString("smsLanguage").ifBlank { defaultLanguage(context) })
        }
    }.getOrDefault(emptyList())

    fun add(context: Context, name: String, number: String): List<TrustedContact> {
        val normalized = PhoneNumberUtils.normalizeNumber(number)
        require(normalized.count(Char::isDigit) in 7..15) { "Enter a valid phone number" }
        val current = load(context)
        val language = current.firstOrNull { it.number == normalized }?.smsLanguage ?: defaultLanguage(context)
        val contact = TrustedContact(name.trim().ifBlank { normalized }, normalized, language)
        val next = current.filterNot { it.number == normalized } + contact
        save(context, next)
        return next
    }

    fun remove(context: Context, number: String): List<TrustedContact> = load(context)
        .filterNot { it.number == number }
        .also { save(context, it) }

    fun setSmsLanguage(context: Context, number: String, language: String): List<TrustedContact> {
        require(language in setOf("en", "hi", "bn", "mr", "ta"))
        return load(context).map { if (it.number == number) it.copy(smsLanguage = language) else it }
            .also { save(context, it) }
    }

    private fun defaultLanguage(context: Context): String =
        AppLanguage.get(context).takeIf { it in setOf("en", "hi", "bn", "mr", "ta") }
            ?: java.util.Locale.getDefault().language.takeIf { it in setOf("en", "hi", "bn", "mr", "ta") }
            ?: "en"

    private fun save(context: Context, contacts: List<TrustedContact>) {
        val array = JSONArray()
        contacts.forEach { array.put(JSONObject().put("name", it.name).put("number", it.number)
            .put("smsLanguage", it.smsLanguage)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("entries", array.toString()).apply()
    }
}
