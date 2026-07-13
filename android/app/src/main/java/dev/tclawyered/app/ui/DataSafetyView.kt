package dev.tclawyered.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.tclawyered.app.data.safety.BreachClient
import dev.tclawyered.app.data.safety.ReputationClient
import dev.tclawyered.app.model.Breach
import dev.tclawyered.app.model.ReportedAction

/**
 * Data-safety card (F-05 data-safety feature): known breaches from Have I Been
 * Pwned (factual, matched on-device) plus AI-reported fines/controversies
 * (best-effort, clearly labelled UNVERIFIED). Both degrade to nothing on miss.
 */
@Composable
fun DataSafetyView(domain: String) {
    if (domain.isBlank()) return
    val context = LocalContext.current
    val breachClient = remember { BreachClient(context) }
    val reputationClient = remember { ReputationClient(context) }

    var breaches by remember { mutableStateOf<List<Breach>>(emptyList()) }
    var reported by remember { mutableStateOf<List<ReportedAction>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(domain) {
        breaches = breachClient.forDomain(domain)
        reported = reputationClient.cached(domain).orEmpty()
        loaded = true
    }

    if (!loaded || (breaches.isEmpty() && reported.isEmpty())) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Data safety", style = MaterialTheme.typography.titleMedium)

            if (breaches.isNotEmpty()) {
                Text("Known breaches (Have I Been Pwned)", style = MaterialTheme.typography.labelLarge)
                breaches.forEach { b ->
                    val year = b.date.take(4)
                    val classes = b.dataClasses.take(4).joinToString(", ")
                    Text("• ${b.title} ($year) — exposed: $classes")
                }
            }

            if (reported.isNotEmpty()) {
                Text("Reported fines & controversies", style = MaterialTheme.typography.labelLarge)
                reported.forEach { a ->
                    Text("• ${a.year} ${a.type}: ${a.summary}")
                }
                Text(
                    "AI-generated from public reports — may be incomplete or outdated. Always verify.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
