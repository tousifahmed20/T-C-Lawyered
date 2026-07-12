package dev.tclawyered.app.pipeline

import dev.tclawyered.app.llm.looseJson
import dev.tclawyered.app.model.DataItem
import dev.tclawyered.app.model.Examples
import dev.tclawyered.app.model.GenuineCheck
import dev.tclawyered.app.model.Summary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Coerces raw model output (arbitrary JSON) into our canonical types — the
 * Kotlin port of chunker.js shapeSummary()/validator coercion. Tolerant of the
 * shapes models actually emit (e.g. dataCollected as plain strings OR objects),
 * so a sloppy response never crashes the pipeline.
 */
object SummaryMapper {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): JsonObject =
        json.parseToJsonElement(looseJson(raw)).jsonObject

    /** Shape the summary fields (no diff/genuine — those are added by the pipeline). */
    fun shapeSummary(obj: JsonObject): Summary {
        val ex = (obj["examples"] as? JsonObject) ?: JsonObject(emptyMap())
        return Summary(
            tldr = str(obj["tldr"]),
            keyRisks = strArray(obj["keyRisks"]),
            dataCollected = dataItems(obj["dataCollected"]),
            thirdPartySharing = strArray(obj["thirdPartySharing"]),
            userRights = strArray(obj["userRights"]),
            protectionTips = strArray(obj["protectionTips"]),
            examples = Examples(
                keyRisks = str(ex["keyRisks"]),
                dataCollected = str(ex["dataCollected"]),
                thirdPartySharing = str(ex["thirdPartySharing"]),
                userRights = str(ex["userRights"]),
            ),
        )
    }

    fun genuineCheck(obj: JsonObject): GenuineCheck = GenuineCheck(
        genuine = (obj["genuine"] as? JsonPrimitive)?.booleanOrNull ?: false,
        confidence = clampConfidence((obj["confidence"] as? JsonPrimitive)?.intOrNull ?: 0),
        reason = str(obj["reason"]).ifEmpty { "No reason provided." },
    )

    /** Section-summary result {summary, points} for the chunked path. */
    fun section(obj: JsonObject): Pair<String, List<String>> =
        str(obj["summary"]) to strArray(obj["points"])

    private fun clampConfidence(n: Int): Int = n.coerceIn(0, 100)

    private fun str(el: kotlinx.serialization.json.JsonElement?): String =
        (el as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: ""

    private fun strArray(el: kotlinx.serialization.json.JsonElement?): List<String> =
        (el as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() }
            .filter { it.isNotEmpty() }

    private fun dataItems(el: kotlinx.serialization.json.JsonElement?): List<DataItem> =
        (el as? JsonArray).orEmpty().mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.content.trim().takeIf { it.isNotEmpty() }?.let { DataItem(it, "") }
                is JsonObject -> {
                    val label = str(item["item"]).ifEmpty { str(item["label"]) }
                    label.takeIf { it.isNotEmpty() }?.let { DataItem(it, str(item["detail"])) }
                }
                else -> null
            }
        }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
