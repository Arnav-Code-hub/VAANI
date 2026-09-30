package com.bithead.shelter.emergency

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.bithead.shelter.data.TrustedContact
import com.bithead.shelter.data.AppDatabase
import com.bithead.shelter.data.SmsOutbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ContactAlertStatus(
    val name: String,
    val number: String,
    val status: String,
    val receiptStatus: String? = null,
    val receiptEvidenceId: Long? = null
)

internal data class SmsPartOutcome(val display: String, val stored: String)

internal fun summarizeSmsParts(
    partCount: Int,
    sent: Set<String>,
    delivered: Set<String>,
    failures: Set<String>
): SmsPartOutcome {
    val sendFailure = failures.firstOrNull { it.startsWith("send:") }
    val deliveryFailure = failures.firstOrNull { it.startsWith("delivery:") }
    return when {
        sendFailure != null -> SmsPartOutcome("Send failed (${sendFailure.substringAfterLast(':')})", "FAILED")
        deliveryFailure != null -> SmsPartOutcome("Delivery failed (${deliveryFailure.substringAfterLast(':')})", "DELIVERY_FAILED")
        delivered.size >= partCount -> SmsPartOutcome("Delivered", "DELIVERED")
        sent.size >= partCount && delivered.isEmpty() -> SmsPartOutcome("Sent", "SENT")
        sent.size >= partCount -> SmsPartOutcome("Sent • delivery ${delivered.size}/$partCount", "SENT")
        else -> SmsPartOutcome("Sending ${sent.size}/$partCount", "PENDING")
    }
}

object EmergencySms {
    private const val PREFS = "vaani_sms_status"
    private const val PARTS_PREFS = "vaani_sms_parts"
    val statuses = MutableStateFlow<List<ContactAlertStatus>>(emptyList())

    fun restore(context: Context) {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("entries", "[]")
        statuses.value = runCatching {
            JSONArray(stored).let { array ->
                (0 until array.length()).map { index ->
                    array.getJSONObject(index).let {
                        ContactAlertStatus(it.getString("name"), it.getString("number"), it.getString("status"),
                            it.optString("receiptStatus").takeIf(String::isNotBlank),
                            it.optLong("receiptEvidenceId", -1L).takeIf { id -> id > 0L })
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun send(context: Context, contacts: List<TrustedContact>, location: CachedLocation?) {
        val batchId = UUID.randomUUID().toString()
        if (contacts.isEmpty()) {
            persist(context, emptyList(), batchId)
            return
        }
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val subscription = SubscriptionManager.getDefaultSmsSubscriptionId()
        val ready = permission && subscription != SubscriptionManager.INVALID_SUBSCRIPTION_ID
        val initial = contacts.map {
            ContactAlertStatus(it.name, it.number, when {
                !permission -> "SMS permission missing"
                !ready -> "No default SMS SIM"
                else -> "Sending"
            })
        }
        persist(context, initial, batchId)
        if (!ready) return

        // Safety-critical wording stays in English until each locale template is human-reviewed.
        // TrustedContact.smsLanguage is retained for that future template selection.
        val body = buildString {
            append("VAANI SOS: Please call me. ")
            if (location == null) append("Location unavailable.")
            else {
                append("Last known ")
                append(SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date(location.timestampMillis)))
                append(": https://maps.google.com/?q=")
                append(String.format(Locale.US, "%.6f,%.6f", location.latitude, location.longitude))
            }
        }
        val manager = runCatching { if (android.os.Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscription)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(subscription)
        } }.getOrElse { error ->
            contacts.forEach { update(context, it.number, "Failed: ${error.message ?: "SMS unavailable"}", batchId = batchId) }
            return
        }
        contacts.forEachIndexed { contactIndex, contact ->
            try {
                val parts = ArrayList(manager.divideMessage(body))
                require(parts.isNotEmpty()) { "SMS provider returned no message parts" }
                val sentIntents = ArrayList<PendingIntent>(parts.size)
                val deliveredIntents = ArrayList<PendingIntent>(parts.size)
                parts.indices.forEach { partIndex ->
                    sentIntents += statusIntent(context, SmsStatusReceiver.ACTION_SENT, batchId, contactIndex, contact.number, partIndex, parts.size)
                    deliveredIntents += statusIntent(context, SmsStatusReceiver.ACTION_DELIVERED, batchId, contactIndex, contact.number, partIndex, parts.size)
                }
                manager.sendMultipartTextMessage(contact.number, null, parts, sentIntents, deliveredIntents)
            } catch (error: Exception) {
                update(context, contact.number, "Failed: ${error.message ?: "SMS unavailable"}", batchId = batchId)
            }
        }
    }

    /** Best-effort multipart proof receipt sent only after an evidence row commits. */
    suspend fun sendSealReceipt(context: Context, contacts: List<TrustedContact>, entryId: Long, chainHash: String, incidentId: String?) {
        if (contacts.isEmpty() || chainHash.isBlank()) return
        contacts.forEach { update(context, it.number, "Sending", evidenceId = entryId) }
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val subscription = SubscriptionManager.getDefaultSmsSubscriptionId()
        val body = buildString {
            append("VAANI evidence #$entryId sealed. ")
            if (!incidentId.isNullOrBlank()) append("Incident $incidentId. ")
            append("Proof SHA-256: $chainHash")
        }
        if (!permission || subscription == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            val reason = if (!permission) "SMS permission missing" else "No default SMS SIM"
            recordReceiptFailure(context, contacts, entryId, body, reason)
            contacts.forEach { update(context, it.number, "Failed: $reason", evidenceId = entryId) }
            return
        }
        val manager = runCatching { if (android.os.Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscription)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(subscription)
        } }.getOrElse { error ->
            val reason = error.message ?: "SMS provider unavailable"
            recordReceiptFailure(context, contacts, entryId, body, reason)
            contacts.forEach { update(context, it.number, "Failed: $reason", evidenceId = entryId) }
            return
        }
        val batchId = UUID.randomUUID().toString()
        contacts.forEachIndexed { contactIndex, contact ->
            var outboxId = -1L
            runCatching {
                outboxId = withContext(Dispatchers.IO) {
                    val dao = AppDatabase.get(context).smsOutboxDao()
                    val existing = dao.find("EVIDENCE_RECEIPT", entryId, contact.number)
                    if (existing != null) {
                        update(context, contact.number, existing.status, evidenceId = entryId)
                        return@withContext -1L
                    }
                    dao.insert(SmsOutbox(purpose = "EVIDENCE_RECEIPT", evidenceId = entryId, number = contact.number, body = body))
                }
                if (outboxId < 0L) return@runCatching
                val parts = ArrayList(manager.divideMessage(body))
                require(parts.isNotEmpty()) { "SMS provider returned no message parts" }
                val sent = ArrayList<PendingIntent>(parts.size)
                val delivered = ArrayList<PendingIntent>(parts.size)
                parts.indices.forEach { partIndex ->
                    sent += statusIntent(context, SmsStatusReceiver.ACTION_SENT, batchId, contactIndex, contact.number, partIndex, parts.size, outboxId, entryId)
                    delivered += statusIntent(context, SmsStatusReceiver.ACTION_DELIVERED, batchId, contactIndex, contact.number, partIndex, parts.size, outboxId, entryId)
                }
                manager.sendMultipartTextMessage(contact.number, null, parts, sent, delivered)
            }.onFailure { error ->
                if (outboxId > 0L) withContext(Dispatchers.IO) {
                    AppDatabase.get(context).smsOutboxDao().updateStatus(outboxId, "FAILED")
                }
                update(context, contact.number, "Failed: ${error.message ?: "SMS unavailable"}", evidenceId = entryId)
            }
        }
    }

    private suspend fun recordReceiptFailure(
        context: Context, contacts: List<TrustedContact>, entryId: Long, body: String, reason: String
    ) = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(context).smsOutboxDao()
        contacts.forEach { contact ->
            val existing = dao.find("EVIDENCE_RECEIPT", entryId, contact.number)
            if (existing == null) dao.insert(SmsOutbox(
                purpose = "EVIDENCE_RECEIPT", evidenceId = entryId, number = contact.number,
                body = body, status = "FAILED: $reason"))
            else if (existing.status == "PENDING") dao.updateStatus(existing.id, "FAILED: $reason")
        }
    }

    private fun statusIntent(
        context: Context,
        action: String,
        batchId: String,
        contactIndex: Int,
        number: String,
        partIndex: Int,
        partCount: Int,
        outboxId: Long = -1L,
        evidenceId: Long? = null
    ): PendingIntent {
        val kind = if (action == SmsStatusReceiver.ACTION_SENT) "sent" else "delivered"
        val identity = "vaani-sms://status/$batchId/$contactIndex/$partIndex/$kind"
        val intent = Intent(context, SmsStatusReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse(identity))
            .putExtra("batchId", batchId)
            .putExtra("number", number)
            .putExtra("partIndex", partIndex)
            .putExtra("partCount", partCount)
            .putExtra("outboxId", outboxId)
            .putExtra("evidenceId", evidenceId ?: -1L)
        return PendingIntent.getBroadcast(
            context,
            identity.hashCode(),
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    @Synchronized
    fun update(context: Context, number: String, status: String, evidenceId: Long? = null, batchId: String? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (evidenceId == null && batchId != null && prefs.getString("batchId", null) != batchId) return
        persist(context, statuses.value.map { current ->
            if (current.number != number) current
            else if (evidenceId == null) current.copy(status = status)
            else if (current.receiptEvidenceId != null && current.receiptEvidenceId > evidenceId) current
            else current.copy(receiptStatus = status, receiptEvidenceId = evidenceId)
        })
    }

    @Synchronized
    fun updatePart(
        context: Context,
        batchId: String,
        number: String,
        partIndex: Int,
        partCount: Int,
        delivered: Boolean,
        success: Boolean,
        resultCode: Int,
        evidenceId: Long?
    ): String {
        val prefs = context.getSharedPreferences(PARTS_PREFS, Context.MODE_PRIVATE)
        val prefix = "$batchId|$number"
        val sent = prefs.getStringSet("$prefix|sent", emptySet()).orEmpty().toMutableSet()
        val received = prefs.getStringSet("$prefix|delivered", emptySet()).orEmpty().toMutableSet()
        val failures = prefs.getStringSet("$prefix|failures", emptySet()).orEmpty().toMutableSet()
        val partKey = partIndex.toString()
        when {
            !success -> failures += "${if (delivered) "delivery" else "send"}:$partIndex:$resultCode"
            delivered -> received += partKey
            else -> sent += partKey
        }
        prefs.edit()
            .putStringSet("$prefix|sent", sent)
            .putStringSet("$prefix|delivered", received)
            .putStringSet("$prefix|failures", failures)
            .apply()

        val outcome = summarizeSmsParts(partCount, sent, received, failures)
        update(context, number, outcome.display, evidenceId = evidenceId, batchId = batchId)
        return outcome.stored
    }

    private fun persist(context: Context, values: List<ContactAlertStatus>, batchId: String? = null) {
        statuses.value = values
        val array = JSONArray()
        values.forEach { current ->
            array.put(JSONObject().put("name", current.name).put("number", current.number)
                .put("status", current.status).put("receiptStatus", current.receiptStatus ?: "")
                .put("receiptEvidenceId", current.receiptEvidenceId ?: -1L))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("entries", array.toString()).apply {
            if (batchId != null) putString("batchId", batchId)
        }.apply()
    }
}

class SmsStatusReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SENT = "com.bithead.shelter.SMS_SENT"
        const val ACTION_DELIVERED = "com.bithead.shelter.SMS_DELIVERED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val number = intent.getStringExtra("number") ?: return
        val batchId = intent.getStringExtra("batchId") ?: return
        val partIndex = intent.getIntExtra("partIndex", -1)
        val partCount = intent.getIntExtra("partCount", 0)
        if (partIndex < 0 || partCount <= 0) return
        EmergencySms.restore(context)
        val delivered = when (intent.action) {
            ACTION_SENT -> false
            ACTION_DELIVERED -> true
            else -> return
        }
        val status = EmergencySms.updatePart(
            context = context,
            batchId = batchId,
            number = number,
            partIndex = partIndex,
            partCount = partCount,
            delivered = delivered,
            success = resultCode == Activity.RESULT_OK,
            resultCode = resultCode,
            evidenceId = intent.getLongExtra("evidenceId", -1L).takeIf { it > 0L }
        )
        val outboxId = intent.getLongExtra("outboxId", -1L)
        if (outboxId > 0L) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try { AppDatabase.get(context).smsOutboxDao().updateStatus(outboxId, status) }
                finally { pending.finish() }
            }
        }
    }
}
