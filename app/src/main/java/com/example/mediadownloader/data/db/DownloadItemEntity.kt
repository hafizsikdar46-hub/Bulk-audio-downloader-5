package com.example.mediadownloader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

object DownloadStatus {
    const val PENDING = "PENDING"
    const val RESOLVING = "RESOLVING"
    const val DOWNLOADING = "DOWNLOADING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
}

@Entity(tableName = "downloads")
data class DownloadItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val sourceUrl: String,
    val resolvedUrl: String? = null,
    val format: String, // "MP3" or "MP4"
    val playlistTitle: String? = null,
    val thumbnailUrl: String? = null,
    val duration: Long = 0L, // in seconds
    val fileUri: String? = null, // content:// or file://
    val filePath: String? = null, // absolute local path
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val progress: Int = 0, // 0 - 100
    val status: String = DownloadStatus.PENDING,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)
