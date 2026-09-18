package com.example.mediadownloader.downloader

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DownloadWorker(private val context: Context) {

    fun executeQueueAsync() {
        DownloadService.startQueue(context)
    }

    companion object {
        fun enqueue(context: Context) {
            DownloadService.startQueue(context)
        }
    }
}
