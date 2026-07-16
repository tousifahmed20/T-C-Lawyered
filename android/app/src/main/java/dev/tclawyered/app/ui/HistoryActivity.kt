package dev.tclawyered.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tclawyered.app.data.local.LocalStore
import dev.tclawyered.app.data.local.SnapshotEntity
import dev.tclawyered.app.model.PolicyType
import dev.tclawyered.app.model.Summary
import dev.tclawyered.app.ui.theme.TcTheme
import kotlinx.serialization.json.Json
import java.text.DateFormat
import java.util.Date

/**
 * History (F-09): every policy version seen on this device, newest first. Tap a
 * row to expand its stored summary. All local — the hive never knows.
 */
class HistoryActivity : ComponentActivity() {
    private val store by lazy { LocalStore(applicationContext) }
    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TcTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HistoryScreen(store, json)
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(store: LocalStore, json: Json) {
    var rows by remember { mutableStateOf<List<SnapshotEntity>>(emptyList()) }
    var expanded by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { rows = store.recent() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("History", style = MaterialTheme.typography.headlineSmall)
        if (rows.isEmpty()) {
            Text("No policies summarized on this device yet.", style = MaterialTheme.typography.bodyMedium)
        }
        rows.forEach { row ->
            val open = expanded == row.hash
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = if (open) null else row.hash },
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val date = DateFormat.getDateInstance().format(Date(row.lastCheckedAt))
                    val label = PolicyType.fromWire(row.policyType)?.name ?: row.policyType
                    Text("${row.domain} · $label", style = MaterialTheme.typography.titleMedium)
                    Text(date, style = MaterialTheme.typography.bodySmall)
                    if (open) {
                        val summary = runCatching {
                            json.decodeFromString(Summary.serializer(), row.summaryJson)
                        }.getOrDefault(Summary())
                        SummaryView(summary, source = "local", scannedAt = row.lastCheckedAt)
                    } else {
                        val summary = runCatching {
                            json.decodeFromString(Summary.serializer(), row.summaryJson)
                        }.getOrNull()
                        Text(summary?.tldr?.ifEmpty { "Tap to view" } ?: "Tap to view")
                    }
                }
            }
        }
    }
}
