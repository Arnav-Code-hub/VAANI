package com.bithead.shelter.ai

import com.bithead.shelter.data.Evidence
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Turns a threat label + score into a short, plain-language sentence a
 * non-technical reader (survivor, advocate, officer) can understand at a
 * glance, instead of a bare number.
 */
object IncidentSummary {

    private fun confidenceWord(score: Int): String = when {
        score >= 80 -> "high confidence"
        score >= 55 -> "moderate confidence"
        score >= 25 -> "low confidence"
        else -> "very low confidence"
    }

    private fun cleanLabel(rawLabel: String): String =
        rawLabel.replace("(Loud Peak)", "").trim().lowercase()

    fun describe(evidence: Evidence): String {
        val time = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(evidence.createdAt)
        val label = cleanLabel(evidence.threatLabel)
        val confidence = confidenceWord(evidence.threatScore)
        val loudNote = if (evidence.threatLabel.contains("Loud Peak")) " at a notably loud volume" else ""
        val locationNote = if (evidence.latitude != null && evidence.longitude != null) {
            " Location was recorded at the time of capture."
        } else {
            " No location was available for this entry."
        }
        return "On $time, audio consistent with \"$label\" was detected$loudNote " +
            "(${evidence.threatScore}/100, $confidence).$locationNote"
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
