package ru.d00g00d.apkstore.downloader

import android.app.DownloadManager
import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class DownloadTracker(private val context: Context) {
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun getProgress(downloadId: Long): Flow<Int> = flow {
        var isDownloading = true
        while (isDownloading) {
            val query = DownloadManager.Query().setFilterById(downloadId)
            downloadManager.query(query).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val status = cursor.getInt(statusIndex)

                    if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                        isDownloading = false
                        emit(if (status == DownloadManager.STATUS_SUCCESSFUL) 100 else -1)
                    } else {
                        val downloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val totalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                        val downloaded = cursor.getLong(downloadedIndex)
                        val total = cursor.getLong(totalIndex)

                        if (total > 0L) {
                            val progress = ((downloaded * 100L) / total).toInt()
                            emit(progress)
                        } else {
                            emit(0)
                        }
                    }
                } else {
                    // Download not found
                    isDownloading = false
                    emit(-1)
                }
            }
            if (isDownloading) {
                delay(500) // Poll twice a second
            }
        }
    }
}
