package ru.d00g00d.apkstore

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import ru.d00g00d.apkstore.di.appModule
import ru.d00g00d.apkstore.utils.FileLogger
import timber.log.Timber

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // Setup Timber
        Timber.plant(Timber.DebugTree())
        Timber.plant(FileLogger(this))

        // Setup Koin
        startKoin {
            androidLogger()
            androidContext(this@App)
            modules(appModule)
        }
    }
}
