package com.example.mediadownloader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.example.R
import com.example.mediadownloader.data.db.AppDatabase
import com.example.mediadownloader.data.repository.DownloadRepository
import com.example.mediadownloader.downloader.DownloadManager
import com.example.mediadownloader.downloader.DownloadService
import com.example.mediadownloader.resolver.CompositeMediaResolver
import com.example.mediadownloader.resolver.DirectMediaResolver
import com.example.mediadownloader.resolver.YouTubeResolver

class MediaDownloaderApp : Application() {

    lateinit var database: AppDatabase
        private set
    lateinit var repository: DownloadRepository
        private set
    lateinit var resolver: CompositeMediaResolver
        private set
    lateinit var downloadManager: DownloadManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = AppDatabase.getInstance(this)
        repository = DownloadRepository(database.downloadDao())
        resolver = CompositeMediaResolver(
            listOf(
                YouTubeResolver(),
                DirectMediaResolver()
            )
        )
        downloadManager = DownloadManager.getInstance(this, repository, resolver)

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val activeChannel = NotificationChannel(
                DownloadService.CHANNEL_ACTIVE,
                getString(R.string.channel_active_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_active_desc)
                setShowBadge(false)
            }

            val completedChannel = NotificationChannel(
                DownloadService.CHANNEL_COMPLETED,
                getString(R.string.channel_completed_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.channel_completed_desc)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(activeChannel)
            notificationManager.createNotificationChannel(completedChannel)
        }
    }

    companion object {
        lateinit var instance: MediaDownloaderApp
            private set
    }
}
