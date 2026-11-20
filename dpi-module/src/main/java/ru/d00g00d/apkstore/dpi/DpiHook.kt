package ru.d00g00d.apkstore.dpi

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import top.canyie.pine.PineConfig
import java.io.File
import java.io.FileInputStream
import java.util.Properties

class DpiHook : IXposedHookLoadPackage {

    companion object {
        const val TAG = "ApkStoreDPI"
        // Файл настроек, который ApkStore будет класть при патчинге / установке
        const val CONFIG_FILE = "/data/local/tmp/apkstore_dpi_config.properties"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Пропускаем системные фреймворки, хукаем только реальные приложухи
        if (lpparam.packageName == "android" || lpparam.packageName.startsWith("com.android.")) return

        try {
            // Инициализация Pine (если работает через LSPatch)
            PineConfig.debug = true
            PineConfig.debuggable = true

            // Захватываем загрузку Application для настройки DPI в самом начале
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        applyDpiMod(param.args[0] as Context)
                    }
                }
            )

        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Error initializing hook for ${lpparam.packageName}: ${e.message}")
        }
    }

    private fun applyDpiMod(context: Context) {
        try {
            val file = File(CONFIG_FILE)
            if (!file.exists()) return

            val props = Properties()
            FileInputStream(file).use { props.load(it) }

            // Читаем DPI из файла настроек (или ставим дефолт 240, если нету)
            val targetDpi = props.getProperty(context.packageName)?.toIntOrNull() ?: return

            XposedBridge.log("$TAG: Applying DPI $targetDpi to ${context.packageName}")

            // 1. Хукаем DisplayMetrics
            XposedHelpers.findAndHookMethod(
                "android.content.res.ResourcesImpl",
                context.classLoader,
                "getDisplayMetrics",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val metrics = param.result as? DisplayMetrics ?: return
                        metrics.densityDpi = targetDpi
                        metrics.density = targetDpi / 160f
                        metrics.scaledDensity = metrics.density
                    }
                }
            )

            // 2. Хукаем Configuration
            XposedHelpers.findAndHookMethod(
                Configuration::class.java,
                "updateFrom",
                Configuration::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = param.thisObject as Configuration
                        config.densityDpi = targetDpi
                    }
                }
            )

        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Error applying DPI mod: ${e.message}")
        }
    }
}
