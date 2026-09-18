package com.example.mediadownloader.downloader

sealed class DownloadCommand {
    data class Start(val id: Long) : DownloadCommand()
    data class Cancel(val id: Long) : DownloadCommand()
    object CancelAll : DownloadCommand()
    data class Retry(val id: Long) : DownloadCommand()
    object RetryAllFailed : DownloadCommand()
}

data class ActiveDownloadProgress(
    val id: Long,
    val title: String,
    val format: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val progress: Int,
    val status: String,
    val currentItemIndex: Int = 1,
    val totalItems: Int = 1
)
