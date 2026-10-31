// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.data.db

import androidx.paging.PagingSource
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

internal const val MAX_IMAGE_RECORDS = 512
private const val IMAGE_RECOVERY_QUERY_LIMIT = MAX_IMAGE_RECORDS + 1

/** Private payload metadata; the existing text/HTML history is not rewritten. */
@Entity(tableName = "clipboard_image_payload", indices = [Index(value = ["sha256"], unique = true)])
data class ClipboardImagePayload(
    @PrimaryKey val id: String,
    val sha256: String,
    val mimeType: String,
    val byteCount: Long,
    val width: Int,
    val height: Int,
    val createdAt: Long,
    val pinned: Boolean,
    @ColumnInfo(defaultValue = "1") val ready: Boolean = true,
)

@Dao
interface ClipboardImagePayloadDao {
    @Insert
    suspend fun insert(payload: ClipboardImagePayload)

    @Query("SELECT * FROM clipboard_image_payload WHERE id = :id AND ready = 1 LIMIT 1")
    suspend fun get(id: String): ClipboardImagePayload?

    @Query("SELECT * FROM clipboard_image_payload WHERE sha256 = :sha256 AND ready = 1 LIMIT 1")
    suspend fun findByHash(sha256: String): ClipboardImagePayload?

    @Query("SELECT * FROM clipboard_image_payload WHERE ready = 1 ORDER BY pinned DESC, createdAt DESC, id DESC")
    fun readyImages(): PagingSource<Int, ClipboardImagePayload>

    @Query("UPDATE clipboard_image_payload SET createdAt = :time WHERE id = :id AND ready = 1")
    suspend fun refreshReady(id: String, time: Long): Int

    @Query("UPDATE clipboard_image_payload SET pinned = :pinned WHERE id = :id AND ready = 1")
    suspend fun updateReadyPinned(id: String, pinned: Boolean): Int

    @Query("UPDATE clipboard_image_payload SET ready = 0 WHERE id = :id AND ready = 1")
    suspend fun markPendingForDelete(id: String): Int

    @Query("UPDATE clipboard_image_payload SET ready = 1 WHERE id = :id AND ready = 0")
    suspend fun markReadyForCommit(id: String): Int

    @Query("SELECT COUNT(*) FROM clipboard_image_payload")
    suspend fun recordCount(): Long

    @Query("SELECT * FROM clipboard_image_payload WHERE ready = 0 LIMIT $IMAGE_RECOVERY_QUERY_LIMIT")
    suspend fun pendingForRecovery(): List<ClipboardImagePayload>

    @Query("SELECT * FROM clipboard_image_payload LIMIT $IMAGE_RECOVERY_QUERY_LIMIT")
    suspend fun allForRecovery(): List<ClipboardImagePayload>

    @Query("DELETE FROM clipboard_image_payload WHERE id = :id AND ready = 0")
    suspend fun deletePendingForRecovery(id: String): Int
}
