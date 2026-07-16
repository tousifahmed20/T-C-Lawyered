package dev.tclawyered.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.tclawyered.app.audio.Tts
import dev.tclawyered.app.core.Constants
import dev.tclawyered.app.model.Summary
import dev.tclawyered.app.pipeline.Validator
import java.text.DateFormat
import java.util.Date

/**
 * Renders a structured Summary (F-09). Minimal but functional for Slice 3 — the
 * polished cards, severity badges, and audio controls are Slice 4.
 */
@Composable
fun SummaryView(summary: Summary, source: String, scannedAt: Long) {
    // Audio (F-10): reads TL;DR + Key Risks aloud with the free Android voice.
    val context = LocalContext.current
    val tts = remember { Tts(context) }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Responsibility: if the document doesn't pass the authenticity gate, say
        // so loudly at the top — the structured summary below can otherwise read
        // as an authoritative policy even when the source isn't one.
        summary.genuineCheck?.let { gc ->
            if (!Validator.passesUploadGate(gc)) {
                val warn = Color(Constants.SeverityColors.HIGH)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, warn),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "This may not be a genuine policy",
                            color = warn,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Authenticity ${gc.confidence}% — the summary below may be " +
                                "unreliable. Double-check against the original document.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (gc.reason.isNotBlank()) {
                            Text(gc.reason, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val spoken = buildString {
                    append(summary.tldr)
                    if (summary.keyRisks.isNotEmpty()) {
                        append(". Key risks: ")
                        append(summary.keyRisks.joinToString(". "))
                    }
                }
                tts.speak(spoken)
            }) { Text("🔊 Listen") }
            OutlinedButton(onClick = { tts.stop() }) { Text("Stop") }
        }

        Section("TL;DR") { Text(summary.tldr.ifEmpty { "—" }) }

        if (summary.whatChanged != null) {
            Section("What changed") {
                summary.changesSeverity?.let { sev ->
                    Text(
                        "Severity: ${sev.uppercase()}",
                        color = severityColor(sev),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Text(summary.whatChanged)
                summary.changeList.forEach { Text("• $it") }
            }
        }

        BulletSection("Key risks", summary.keyRisks, summary.examples.keyRisks)

        if (summary.dataCollected.isNotEmpty()) {
            Section("Data collected") {
                summary.dataCollected.forEach { item ->
                    Text("• ${item.item}${if (item.detail.isNotEmpty()) " — ${item.detail}" else ""}")
                }
                if (summary.examples.dataCollected.isNotEmpty()) {
                    Text("e.g. ${summary.examples.dataCollected}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        BulletSection("Third-party sharing", summary.thirdPartySharing, summary.examples.thirdPartySharing)
        BulletSection("Your rights", summary.userRights, summary.examples.userRights)

        if (summary.protectionTips.isNotEmpty()) {
            BulletSection("Protect your data", summary.protectionTips, "")
        }

        val when0 = DateFormat.getDateInstance().format(Date(scannedAt))
        val auth = summary.genuineCheck?.let { "Authenticity ${it.confidence}% · " } ?: ""
        Text(
            "${auth}Last checked $when0 · source: $source",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun BulletSection(title: String, items: List<String>, example: String) {
    if (items.isEmpty()) return
    Section(title) {
        items.forEach { Text("• $it") }
        if (example.isNotEmpty()) {
            Text("e.g. $example", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun severityColor(severity: String): Color = Color(
    when (severity) {
        "high" -> Constants.SeverityColors.HIGH
        "medium" -> Constants.SeverityColors.MEDIUM
        "low" -> Constants.SeverityColors.LOW
        else -> Constants.SeverityColors.NONE
    },
)

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
