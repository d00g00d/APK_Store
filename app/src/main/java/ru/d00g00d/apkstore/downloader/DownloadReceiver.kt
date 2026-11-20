package ru.d00g00d.apkstore.downloader

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ru.d00g00d.apkstore.core.adb.AdbClient
import ru.d00g00d.apkstore.core.permission.PermissionManager
import timber.log.Timber
import java.io.File
import java.io.FileInputStream

class DownloadReceiver : BroadcastReceiver(), KoinComponent {

    private val adbClient: AdbClient by inject()
    private val permissionManager: PermissionManager by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == -1L) return

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(id)

            downloadManager.query(query).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val status = cursor.getInt(statusIndex)

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        val localUriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                        val uriString = cursor.getString(localUriIndex)
                        if (uriString != null) {
                            val file = File(android.net.Uri.parse(uriString).path ?: "")
                            if (file.exists()) {
                                Timber.d("Download complete: ${file.absolutePath}. Starting ADB install...")
                                installApk(context, file)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Временно ставим на текущего пользователя
                val userId = android.os.Process.myUserHandle().hashCode()
                
                // Получаем package name из APK для последующей выдачи прав
                val pi = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
                val packageName = pi?.packageName

                FileInputStream(apkFile).use { fis ->
                    val result = adbClient.streamInstallApk(
                        apkStream = fis,
                        totalBytes = apkFile.length(),
                        userId = userId
                    )

                    if (result.isSuccess) {
                        Timber.i("Install success: ${result.getOrNull()}")
                        // Автоматически выдаем все спец. права, если мы смогли получить packageName
                        if (packageName != null) {
                            Timber.d("Granting permissions for $packageName")
                            permissionManager.grantAll(packageName, userId)
                        }
                    } else {
                        Timber.e("Install failed: ${result.exceptionOrNull()?.message}")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Install crashed")
            } finally {
                // apkFile.delete() // Можно удалить после успешной установки
            }
        }
    }
}
