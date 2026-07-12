package dev.tclawyered.app.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The structured summary blob (F-06). Mirrors the extension's SummaryJSON so a
 * summary produced on the phone is byte-compatible with one from the extension
 * and stored identically in the hive (JSONB, schema-flexible).
 */
@Serializable
data class Summary(
    val tldr: String = "",
    val keyRisks: List<String> = emptyList(),
    val dataCollected: List<DataItem> = emptyList(),
    val thirdPartySharing: List<String> = emptyList(),
    val userRights: List<String> = emptyList(),
    val protectionTips: List<String> = emptyList(),
    val examples: Examples = Examples(),
    val whatChanged: String? = null,
    val changeList: List<String> = emptyList(),
    val changesSeverity: String? = null,
    val previousSeenAt: Long? = null,
    val genuineCheck: GenuineCheck? = null,
)

@Serializable
data class DataItem(
    val item: String = "",
    val detail: String = "",
)

@Serializable
data class Examples(
    val keyRisks: String = "",
    val dataCollected: String = "",
    val thirdPartySharing: String = "",
    val userRights: String = "",
)

@Serializable
data class GenuineCheck(
    val genuine: Boolean = false,
    val confidence: Int = 0,
    val reason: String = "",
)

enum class Severity(@SerialName("wire") val wire: String) {
    NONE("none"), LOW("low"), MEDIUM("medium"), HIGH("high");

    companion object {
        fun fromWire(value: String?): Severity =
            entries.firstOrNull { it.wire == value } ?: NONE
    }
}
