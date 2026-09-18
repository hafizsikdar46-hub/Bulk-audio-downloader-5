package com.example.mediadownloader.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: DownloadItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DownloadItemEntity>): List<Long>

    @Update
    suspend fun update(item: DownloadItemEntity)

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DownloadItemEntity?

    @Query("SELECT * FROM downloads WHERE sourceUrl = :sourceUrl ORDER BY createdAt DESC")
    suspend fun getByUrl(sourceUrl: String): List<DownloadItemEntity>

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun getAllDownloads(): Flow<List<DownloadItemEntity>>

    @Query("SELECT * FROM downloads WHERE status = 'COMPLETED' ORDER BY completedAt DESC, createdAt DESC")
    fun getCompletedDownloads(): Flow<List<DownloadItemEntity>>

    @Query("SELECT * FROM downloads WHERE status IN ('PENDING', 'RESOLVING', 'DOWNLOADING') ORDER BY createdAt ASC")
    fun getActiveDownloads(): Flow<List<DownloadItemEntity>>

    @Query("SELECT * FROM downloads WHERE status = 'FAILED' ORDER BY createdAt DESC")
    fun getFailedDownloads(): Flow<List<DownloadItemEntity>>

    @Query("SELECT * FROM downloads WHERE status = 'PENDING' ORDER BY createdAt ASC")
    suspend fun getPendingDownloads(): List<DownloadItemEntity>

    @Delete
    suspend fun delete(item: DownloadItemEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM downloads WHERE status = 'COMPLETED'")
    suspend fun clearCompleted()

    @Query("UPDATE downloads SET status = 'CANCELLED' WHERE status IN ('PENDING', 'RESOLVING', 'DOWNLOADING')")
    suspend fun cancelAllActive()

    @Query("UPDATE downloads SET status = 'PENDING', errorMessage = null, progress = 0, downloadedBytes = 0 WHERE id = :id")
    suspend fun retryFailed(id: Long)

    @Query("UPDATE downloads SET status = 'PENDING', errorMessage = null, progress = 0, downloadedBytes = 0 WHERE status = 'FAILED'")
    suspend fun retryAllFailed()

    @Query("UPDATE downloads SET downloadedBytes = :downloadedBytes, totalBytes = :totalBytes, progress = :progress WHERE id = :id")
    suspend fun updateProgress(id: Long, downloadedBytes: Long, totalBytes: Long, progress: Int)

    @Query("UPDATE downloads SET status = :status, errorMessage = :errorMessage WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, errorMessage: String? = null)

    @Query("UPDATE downloads SET status = 'COMPLETED', fileUri = :fileUri, filePath = :filePath, totalBytes = :totalBytes, downloadedBytes = :totalBytes, progress = 100, completedAt = :completedAt WHERE id = :id")
    suspend fun markCompleted(id: Long, fileUri: String, filePath: String, totalBytes: Long, completedAt: Long)
}
