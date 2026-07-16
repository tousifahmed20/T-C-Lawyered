package dev.tclawyered.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.tclawyered.app.data.ProviderState
import dev.tclawyered.app.data.SettingsRepository
import dev.tclawyered.app.llm.LlmClient
import dev.tclawyered.app.ui.theme.TcTheme
import kotlinx.coroutines.launch

/**
 * Settings screen (F-11): one card per provider with an encrypted key field, a
 * model picker, and Save / Test / Use-this actions. OpenRouter is flagged
 * "Free · Recommended" to steer users to the zero-cost path.
 */
class SettingsActivity : ComponentActivity() {
    private val repo by lazy { SettingsRepository(applicationContext) }
    private val llm by lazy { LlmClient() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TcTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(repo, llm)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(repo: SettingsRepository, llm: LlmClient) {
    val scope = rememberCoroutineScope()
    var states by remember { mutableStateOf<List<ProviderState>>(emptyList()) }

    suspend fun reload() { states = repo.snapshot() }
    androidx.compose.runtime.LaunchedEffect(Unit) { reload() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Your API key never leaves this device except to the provider you choose. " +
                "Keys are encrypted at rest (Android Keystore, AES-GCM).",
            style = MaterialTheme.typography.bodySmall,
        )

        states.forEach { state ->
            ProviderCard(
                state = state,
                onSave = { key, model ->
                    scope.launch { repo.saveProvider(state.provider, key, model); reload() }
                },
                onActivate = {
                    scope.launch { repo.setActive(state.provider); reload() }
                },
                onTest = { key, model, report ->
                    scope.launch {
                        // Persist first so the test uses exactly what the user entered.
                        if (key.isNotBlank()) repo.saveProvider(state.provider, key, model)
                        val cfg = repo.getConfig(state.provider)
                        report(if (cfg == null) "No key saved" else llm.test(cfg) ?: "✓ Working")
                        reload()
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderCard(
    state: ProviderState,
    onSave: (String, String) -> Unit,
    onActivate: () -> Unit,
    onTest: (String, String, (String) -> Unit) -> Unit,
) {
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(state.model) }
    var expanded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.provider.label, style = MaterialTheme.typography.titleMedium)
                if (state.provider.isFreeRecommended) Text("· Free · Recommended")
                if (state.configured) Text("· Configured")
                if (state.active) Text("· Active ✓")
            }

            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text(if (state.configured) "Replace API key" else "API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = model,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    state.provider.models.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m) },
                            onClick = { model = m; expanded = false },
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(key, model); status = "Saved" }) { Text("Save") }
                TextButton(onClick = { status = "Testing…"; onTest(key, model) { status = it } }) {
                    Text("Test")
                }
                TextButton(onClick = onActivate) { Text(if (state.active) "Active ✓" else "Use this") }
            }

            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}
