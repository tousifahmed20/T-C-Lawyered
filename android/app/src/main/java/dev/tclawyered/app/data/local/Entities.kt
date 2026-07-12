package dev.tclawyered.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A full policy version snapshot (F-12). `ts` is first-seen (preserved across
 * re-checks); `lastCheckedAt` is refreshed on every write and drives the
 * 2-month staleness re-check — mirroring the extension's snapshots store.
 * The summary is stored as its JSON string (schema-flexible, like the hive JSONB).
 */
@Entity(tableName = "snapshots")
data class SnapshotEntity(
    @PrimaryKey val hash: String,
    val domain: String,
    val policyType: String,
    val rawText: String,
    val summaryJson: String,
    val parentHash: String?,
    val ts: Long,
    val lastCheckedAt: Long,
)

/** Latest-version pointer per domain + policy type (F-02). */
@Entity(tableName = "sites", primaryKeys = ["domain", "policyType"])
data class SiteEntity(
    val domain: String,
    val policyType: String,
    val hash: String,
    val lastSeen: Long,
)
