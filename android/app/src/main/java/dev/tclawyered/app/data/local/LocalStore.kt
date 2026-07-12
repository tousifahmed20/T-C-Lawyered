package dev.tclawyered.app.data.local

import android.content.Context
import dev.tclawyered.app.core.Constants
import dev.tclawyered.app.model.PolicyType
import dev.tclawyered.app.model.Summary
import kotlinx.serialization.json.Json

/**
 * Repository over Room (F-12). Owns Summary<->JSON (stored as a string, like the
 * hive's JSONB) and the 2-month staleness rule that drives auto-re-check.
 */
class LocalStore(context: Context) {
    private val dao = AppDatabase.get(context).policyDao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun snapshot(hash: String): SnapshotEntity? = dao.getSnapshot(hash)

    fun decodeSummary(entity: SnapshotEntity): Summary =
        json.decodeFromString(Summary.serializer(), entity.summaryJson)

    /** True if the cached summary is older than the re-check window. */
    fun isStale(entity: SnapshotEntity): Boolean =
        System.currentTimeMillis() - entity.lastCheckedAt > Constants.RECHECK_TTL_MS

    /** Upsert a snapshot; preserves first-seen `ts`, refreshes `lastCheckedAt`. */
    suspend fun putSnapshot(
        hash: String,
        domain: String,
        type: PolicyType,
        rawText: String,
        summary: Summary,
        parentHash: String?,
    ) {
        val now = System.currentTimeMillis()
        val firstSeen = dao.getSnapshot(hash)?.ts ?: now
        dao.upsertSnapshot(
            SnapshotEntity(
                hash = hash,
                domain = domain,
                policyType = type.wire,
                rawText = rawText,
                summaryJson = json.encodeToString(Summary.serializer(), summary),
                parentHash = parentHash,
                ts = firstSeen,
                lastCheckedAt = now,
            ),
        )
    }

    suspend fun site(domain: String, type: PolicyType): SiteEntity? =
        dao.getSite(domain, type.wire)

    suspend fun putSite(domain: String, type: PolicyType, hash: String) {
        dao.upsertSite(SiteEntity(domain, type.wire, hash, System.currentTimeMillis()))
    }

    suspend fun history(domain: String, type: PolicyType): List<SnapshotEntity> =
        dao.snapshotsForDomain(domain, type.wire)

    /** Auto-prune snapshots older than the retention window (F-12). */
    suspend fun prune(): Int =
        dao.pruneOlderThan(System.currentTimeMillis() - RETENTION_MS)

    companion object {
        private const val RETENTION_MS = 365L * 24 * 60 * 60 * 1000 // 12 months
    }
}
