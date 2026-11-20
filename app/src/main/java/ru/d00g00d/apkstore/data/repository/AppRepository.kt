package ru.d00g00d.apkstore.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.d00g00d.apkstore.data.model.AppModel
import ru.d00g00d.apkstore.data.network.GitHubApiService
import timber.log.Timber

class AppRepository(
    private val apiService: GitHubApiService
) {
    private var cachedApps: List<AppModel>? = null

    suspend fun getApps(forceRefresh: Boolean = false): Result<List<AppModel>> = withContext(Dispatchers.IO) {
        if (!forceRefresh && cachedApps != null) {
            return@withContext Result.success(cachedApps!!)
        }

        try {
            val catalog = apiService.getStoreCatalog()
            cachedApps = catalog.apps
            Result.success(catalog.apps)
        } catch (e: Exception) {
            Timber.e(e, "Error fetching apps from GitHub, using mock data")
            // В случае ошибки (например, файл еще не создан на GitHub) используем заглушку
            val mockData = listOf(
                AppModel(
                    id = "ru.zona.app",
                    name = "Zona",
                    version = "5.0.3",
                    packageName = "ru.zona.app",
                    iconUrl = "https://example.com/zona_icon.png",
                    apkUrl = "https://zona-app.ru/download/upload/?file=zona5.apk",
                    releaseNotes = "Новая версия кинотеатра."
                ),
                AppModel(
                    id = "ru.rustore.app",
                    name = "RuStore",
                    version = "1.114.0.3",
                    packageName = "ru.vk.store",
                    iconUrl = "https://example.com/rustore_icon.png",
                    apkUrl = "https://static.rustore.ru/rustore-releases/stable/RuStore-mobile-1.114.0.3.apk",
                    releaseNotes = "Официальный магазин."
                )
            )
            cachedApps = mockData
            Result.success(mockData)
        }
    }
}
