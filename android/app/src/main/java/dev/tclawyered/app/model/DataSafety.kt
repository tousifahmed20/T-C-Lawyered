package dev.tclawyered.app.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A HIBP breach entry, slimmed to what we render (factual source). */
@Serializable
data class Breach(
    @SerialName("Name") val name: String = "",
    @SerialName("Title") val title: String = "",
    @SerialName("Domain") val domain: String = "",
    @SerialName("BreachDate") val date: String = "", // YYYY-MM-DD
    @SerialName("PwnCount") val count: Long = 0,
    @SerialName("DataClasses") val dataClasses: List<String> = emptyList(),
    @SerialName("IsVerified") val verified: Boolean = false,
)

/** An AI-reported fine/breach/controversy — UNVERIFIED, labelled as such in UI. */
@Serializable
data class ReportedAction(
    val year: String = "",
    val type: String = "controversy", // breach | fine | controversy
    val summary: String = "",
    val confidence: Int = 0,
)
