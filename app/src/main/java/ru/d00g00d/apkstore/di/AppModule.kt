package ru.d00g00d.apkstore.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import retrofit2.Retrofit
import ru.d00g00d.apkstore.core.adb.AdbClient
import ru.d00g00d.apkstore.core.eas.RearDhuEasBridge
import ru.d00g00d.apkstore.core.permission.PermissionManager
import ru.d00g00d.apkstore.data.network.GitHubApiService
import ru.d00g00d.apkstore.data.repository.AppRepository
import ru.d00g00d.apkstore.downloader.ApkDownloader

import ru.d00g00d.apkstore.ui.main.MainViewModel

val appModule = module {
    // Сеть
    single {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val contentType = "application/json".toMediaType()
        
        Retrofit.Builder()
            .baseUrl("https://raw.githubusercontent.com/")
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(GitHubApiService::class.java)
    }

    // Репозиторий
    single { AppRepository(get()) }

    // Загрузчик
    single { ApkDownloader(androidContext()) }

    // Ядро установки (adb, permission, eas)
    single { AdbClient(androidContext()) }
    single { PermissionManager(androidContext(), get()) }
    single { RearDhuEasBridge(androidContext()) }

    // UI
    viewModel { MainViewModel(get(), get()) }
}
