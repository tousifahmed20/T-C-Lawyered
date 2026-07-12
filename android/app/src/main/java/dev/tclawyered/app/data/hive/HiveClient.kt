package dev.tclawyered.app.data.hive

import dev.tclawyered.app.core.Constants
import dev.tclawyered.app.model.PolicyType
import dev.tclawyered.app.model.Summary
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit

/**
 * Hive client (F-04, F-08) — the Kotlin counterpart of background/hive.js.
 * The hive is a read-heavy cache, never a dependency: every call degrades
 * gracefully. A lookup failure returns a miss; an upload failure is swallowed.
 *
 * Speaks the exact contract in docs/PHASE2.md — do not drift.
 */
class HiveClient(
    private val baseUrl: String = Constants.HIVE_BASE_URL,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val lookupClient = OkHttpClient.Builder()
        .callTimeout(Constants.HIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val writeClient = OkHttpClient.Builder()
        .callTimeout(Constants.FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    /** Look up a summary by content hash. Fails to a miss, never throws. */
    fun lookup(hash: String, domain: String): LookupResult {
        return try {
            val url = "$baseUrl/policy".toHttpUrl().newBuilder()
                .addQueryParameter("hash", hash)
                .addQueryParameter("domain", domain)
                .build()
            val req = Request.Builder().url(url).get().build()
            lookupClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return LookupResult(false)
                val body = res.body?.string().orEmpty()
                val parsed = json.decodeFromString<LookupDto>(body)
                if (parsed.found && parsed.summary != null) {
                    LookupResult(true, parsed.summary, parsed.submittedAt)
                } else {
                    LookupResult(false)
                }
            }
        } catch (_: Exception) {
            LookupResult(false)
        }
    }

    /** Version chain for a domain + type. Fails to an empty list. */
    fun history(domain: String, type: PolicyType): List<Version> {
        return try {
            val url = "$baseUrl/policy/history".toHttpUrl().newBuilder()
                .addQueryParameter("domain", domain)
                .addQueryParameter("type", type.wire)
                .build()
            val req = Request.Builder().url(url).get().build()
            lookupClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return emptyList()
                json.decodeFromString<HistoryDto>(res.body?.string().orEmpty()).versions
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Upload a summary (F-08). Fire-and-forget: first-write-wins is enforced
     * server-side, so a 409 is a no-op success. Never throws.
     */
    fun upload(payload: UploadRequest): UploadResult {
        return try {
            val body = json.encodeToString(UploadRequest.serializer(), payload)
                .toRequestBody(jsonMedia)
            val req = Request.Builder().url("$baseUrl/policy").post(body).build()
            writeClient.newCall(req).execute().use { res ->
                when {
                    res.code == 409 -> UploadResult(false, "hash_exists")
                    res.isSuccessful -> UploadResult(true)
                    else -> UploadResult(false, "status_${res.code}")
                }
            }
        } catch (_: Exception) {
            UploadResult(false, "unreachable")
        }
    }
}

/* --------------------------- results / DTOs --------------------------- */

data class LookupResult(val found: Boolean, val summary: Summary? = null, val submittedAt: String? = null)
data class UploadResult(val stored: Boolean, val reason: String? = null)

@Serializable
private data class LookupDto(
    val found: Boolean = false,
    val summary: Summary? = null,
    val submittedAt: String? = null,
)

@Serializable
private data class HistoryDto(val versions: List<Version> = emptyList())

@Serializable
data class Version(
    val hash: String,
    val parentHash: String? = null,
    val submittedAt: String? = null,
)

@Serializable
data class UploadRequest(
    val domain: String,
    val policyType: String,
    val hash: String,
    val parentHash: String? = null,
    val summary: Summary,
    @SerialName("submittedAt") val submittedAt: String,
)
