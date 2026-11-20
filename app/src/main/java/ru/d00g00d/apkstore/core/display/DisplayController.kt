package ru.d00g00d.apkstore.core.display

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.provider.Settings
import timber.log.Timber

class DisplayController(private val context: Context) {

    companion object {
        const val DISPLAY_DRIVER_1001 = 1001
        const val DISPLAY_FULL_1003 = 1003

        private const val POLICY_CONTROL = "policy_control"
        private const val VALUE_IMMERSIVE_ALL = "immersive.full=*"
        private const val VALUE_NORMAL = "null*"
    }

    /**
     * Запуск приложения с опциональным указанием ID дисплея и режима Fullscreen.
     */
    fun launchApp(packageName: String, displayId: Int?, enableFullscreen: Boolean) {
        try {
            // Настройка Fullscreen (policy_control)
            val newPolicy = if (enableFullscreen) VALUE_IMMERSIVE_ALL else VALUE_NORMAL
            runCatching {
                Settings.Global.putString(context.contentResolver, POLICY_CONTROL, newPolicy)
            }.onFailure {
                Timber.e(it, "Failed to set policy_control. Need WRITE_SECURE_SETTINGS permission.")
            }

            // Запуск приложения
            val baseIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (baseIntent == null) {
                Timber.w("No launch intent found for $packageName")
                return
            }

            val component = baseIntent.component
            if (component == null) {
                Timber.w("No component found for $packageName")
                return
            }

            val resumeIntent = Intent().apply {
                this.component = component
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
            }

            val optionsBundle = if (displayId != null) {
                val options = ActivityOptions.makeBasic()
                try {
                    // Используем reflection, если прямого доступа нет, но обычно launchDisplayId доступен
                    // начиная с API 26.
                    options.launchDisplayId = displayId
                } catch (e: Exception) {
                    Timber.w(e, "launchDisplayId not supported or failed")
                }
                options.toBundle()
            } else {
                null
            }

            context.startActivity(resumeIntent, optionsBundle)
            Timber.i("Launched $packageName (displayId=$displayId, fullscreen=$enableFullscreen)")

        } catch (e: Exception) {
            Timber.e(e, "Error launching app $packageName")
        }
    }
}
