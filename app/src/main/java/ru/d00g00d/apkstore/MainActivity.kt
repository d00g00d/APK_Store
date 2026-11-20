package ru.d00g00d.apkstore

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ru.d00g00d.apkstore.ui.main.MainScreen
import ru.d00g00d.apkstore.ui.theme.ApkStoreTheme
import timber.log.Timber

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // TODO: Проверка MultiDisplay. Здесь можно проверить displayId
        // и при необходимости логировать запуск на неверном экране.
        val displayId = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            display?.displayId ?: -1
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.displayId
        }
        Timber.d("MainActivity launched on displayId: $displayId")

        setContent {
            ApkStoreTheme {
                MainScreen()
            }
        }
    }
}
