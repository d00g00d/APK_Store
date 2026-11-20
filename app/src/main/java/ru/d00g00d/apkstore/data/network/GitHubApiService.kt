package ru.d00g00d.apkstore.data.network

import retrofit2.http.GET
import ru.d00g00d.apkstore.data.model.StoreCatalog

interface GitHubApiService {
    @GET("d00g00d/APK_Store/main/store.json")
    suspend fun getStoreCatalog(): StoreCatalog
}
