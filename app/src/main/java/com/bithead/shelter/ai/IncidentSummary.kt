package com.bithead.shelter.ai

import com.bithead.shelter.R
import com.bithead.shelter.data.Evidence
import com.bithead.shelter.i18n.mediaTypeLabel
import com.bithead.shelter.i18n.str
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Turns a threat label + score into a short, plain-language sentence a
 * non-technical reader (survivor, advocate, officer) can understand at a
 * glance, instead of a bare number.
 */
object IncidentSummary {

    private fun confidenceWord(score: Int): String = when {
        score >= 80 -> str(R.string.confidence_high)
        score >= 55 -> str(R.string.confidence_moderate)
        score >= 25 -> str(R.string.confidence_low)
        else -> str(R.string.confidence_very_low)
    }

    // Segmented recordings are labelled "Segment N • <sound>"; the prefix is
    // bookkeeping, not part of what the sound model heard.
    private val segmentPrefix = Regex("""^segment (\d+) • """, RegexOption.IGNORE_CASE)

    private fun cleanLabel(rawLabel: String): String =
        rawLabel.replace("(Loud Peak)", "").trim().replace(segmentPrefix, "")

    /**
     * Labels are stored in English so the database stays stable across
     * languages; known capture sources are translated for display. Sound
     * model classes (YAMNet's 521 English labels) are shown as-is.
     */
    fun displayLabel(stored: String): String {
        val segment = segmentPrefix.find(stored)?.groupValues?.get(1)
        val base = cleanLabel(stored)
        val localized = when (base.lowercase(Locale.ROOT)) {
            "pocket trigger" -> str(R.string.label_pocket_trigger)
            "safeword" -> str(R.string.label_safeword)
            "power-button proxy" -> str(R.string.label_power_button)
            "double-jerk sensor" -> str(R.string.label_double_jerk)
            "recovered audio segment" -> str(R.string.label_recovered_segment)
            "recovered evidence" -> str(R.string.label_recovered_evidence)
            "video evidence" -> str(R.string.video_evidence)
            "image evidence" -> str(R.string.image_evidence)
            else -> base
        }
        return if (segment != null) str(R.string.label_segment, segment.toInt(), localized) else localized
    }

    fun describe(evidence: Evidence): String {
        val clock = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(evidence.createdAt)
        val segment = segmentPrefix.find(evidence.threatLabel)?.groupValues?.get(1)
        val time = if (segment != null) str(R.string.summary_time_part, clock, segment.toInt()) else clock
        val base = cleanLabel(evidence.threatLabel)
        val locationNote = if (evidence.latitude != null && evidence.longitude != null) {
            str(R.string.summary_location_recorded)
        } else {
            str(R.string.summary_location_missing)
        }
        val sentence = if (evidence.threatScore <= 0) {
            // No sound analysis ran (pocket trigger, photo, video, recovered file),
            // so describe the capture itself rather than a detected sound.
            val type = mediaTypeLabel(evidence.mediaType)
            if (base.equals("pocket trigger", ignoreCase = true)) str(R.string.summary_capture_pocket, time, type)
            else str(R.string.summary_capture, time, type)
        } else {
            val loud = evidence.threatLabel.contains("Loud Peak")
            str(if (loud) R.string.summary_sound_loud else R.string.summary_sound,
                time, base.lowercase(Locale.getDefault()), evidence.threatScore, confidenceWord(evidence.threatScore))
        }
        return "$sentence $locationNote"
    }

    /**
     * Builds a shareable JSON "chain of custody" record for one evidence
     * entry: hashes, timestamps, location, and the chain link back to the
     * prior entry — the kind of artifact a lawyer or investigator could
     * independently verify against the file on disk.
     */
    fun toChainOfCustodyJson(evidence: Evidence): String {
        val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(evidence.createdAt)
        return """
        {
          "evidence_id": ${evidence.id},
          "captured_at": "$time",
          "encrypted_file": "${evidence.encryptedFile}",
          "location": ${if (evidence.latitude != null) "{ \"lat\": ${evidence.latitude}, \"lng\": ${evidence.longitude} }" else "null"},
          "threat_label": "${evidence.threatLabel}",
          "threat_score": ${evidence.threatScore},
          "sha256_chain_value": "${evidence.sha256}",
          "previous_chain_value": ${if (evidence.previousHash != null) "\"${evidence.previousHash}\"" else "null"},
          "summary": "${describe(evidence).replace("\"", "\\\"")}",
          "encryption": "AES-256-GCM, key held in Android Keystore (non-exportable)"
        }
        """.trimIndent()
    }
}
