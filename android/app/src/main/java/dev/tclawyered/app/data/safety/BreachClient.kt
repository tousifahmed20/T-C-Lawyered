package dev.tclawyered.app.data.safety

import android.content.Context
import dev.tclawyered.app.core.Constants
import dev.tclawyered.app.core.Domain
import dev.tclawyered.app.model.Breach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Breach history via Have I Been Pwned (factual source) — port of breaches.js.
 *
 * Privacy-preserving: we download HIBP's ENTIRE public breach list and match the
 * domain LOCALLY. The site the user is looking at is never sent to HIBP. The
 * list is cached on disk for a day; every failure degrades to "no data".
 */
class BreachClient(context: Context) {
    private val cacheFile = File(context.cacheDir, "hibp_breaches.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .callTimeout(Constants.FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /** Known breaches for a normalized domain, newest first. */
    suspend fun forDomain(domain: String): List<Breach> = withContext(Dispatchers.IO) {
        if (domain.isBlank()) return@withContext emptyList()
        loadList()
            .filter { it.domain.isNotBlank() && Domain.normalize(it.domain) == domain }
            .sortedByDescending { it.date }
    }

    private fun loadList(): List<Breach> {
        val fresh = cacheFile.exists() &&
            System.currentTimeMillis() - cacheFile.lastModified() < Constants.HIBP_CACHE_TTL_MS
        if (fresh) {
            runCatching { decode(cacheFile.readText()) }.getOrNull()?.let { return it }
        }
        return try {
            val req = Request.Builder()
                .url(Constants.HIBP_BREACHES_URL)
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw IllegalStateException("HIBP ${res.code}")
                val body = res.body?.string().orEmpty()
                val list = decode(body)
                runCatching { cacheFile.writeText(body) }
                list
            }
        } catch (_: Exception) {
            // Stale-but-useful, or empty.
            runCatching { decode(cacheFile.readText()) }.getOrDefault(emptyList())
        }
    }

    private fun decode(text: String): List<Breach> =
        json.decodeFromString(ListSerializer(Breach.serializer()), text)
}
