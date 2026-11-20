package ru.d00g00d.apkstore.downloader

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import timber.log.Timber
import java.io.File

class ApkDownloader(private val context: Context) {

    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun downloadApk(url: String, fileName: String): Long {
        Timber.d("Starting download for $fileName from $url")
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(fileName)
            setDescription("Скачивание обновления...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }

        return try {
            downloadManager.enqueue(request)
        } catch (e: Exception) {
            Timber.e(e, "Failed to enqueue download")
            -1L
        }
    }

    fun getDownloadedFile(fileName: String): File? {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val file = File(dir, fileName)
        return if (file.exists()) file else null
    }
}
