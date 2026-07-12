package dev.tclawyered.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface PolicyDao {

    @Query("SELECT * FROM snapshots WHERE hash = :hash LIMIT 1")
    suspend fun getSnapshot(hash: String): SnapshotEntity?

    @Upsert
    suspend fun upsertSnapshot(snapshot: SnapshotEntity)

    /** History for a domain + type, newest first (F-09). */
    @Query("SELECT * FROM snapshots WHERE domain = :domain AND policyType = :type ORDER BY ts DESC")
    suspend fun snapshotsForDomain(domain: String, type: String): List<SnapshotEntity>

    @Query("SELECT * FROM sites WHERE domain = :domain AND policyType = :type LIMIT 1")
    suspend fun getSite(domain: String, type: String): SiteEntity?

    @Upsert
    suspend fun upsertSite(site: SiteEntity)

    /** Auto-prune snapshots older than the cutoff (F-12). */
    @Query("DELETE FROM snapshots WHERE ts < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long): Int
}
