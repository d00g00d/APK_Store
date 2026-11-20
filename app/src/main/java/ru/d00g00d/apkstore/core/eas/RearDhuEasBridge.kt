package ru.d00g00d.apkstore.core.eas

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.d00g00d.apkstore.core.adb.AdbClient
import ru.d00g00d.apkstore.core.display.DpiConfigStore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

data class RearStatus(
    val isEasBusBound: Boolean = false,
    val isRearScreenPowered: Boolean = false,
    val remoteServices: List<String> = emptyList()
)

data class RearInstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val dpi: Int
)

class RearDhuEasBridge(
    private val context: Context
) {
    companion object {
        const val REAR_AGENT_HOST = "198.18.52.2"
        const val REAR_AGENT_PORT = 18999
        private const val CONNECT_TIMEOUT_MS = 2500
        private const val READ_TIMEOUT_MS = 120_000
    }

    private val _status = MutableStateFlow(RearStatus())
    val status: StateFlow<RearStatus> = _status.asStateFlow()

    private fun baseUrl(): String = "http://$REAR_AGENT_HOST:$REAR_AGENT_PORT"

    fun bind() {
        // Соединение устанавливается напрямую по HTTP к агенту 2-го ряда без системной шины ECARX
    }

    suspend fun refreshStatus(): RearStatus = withContext(Dispatchers.IO) {
        val pingOk = runCatching {
            val conn = (URL("${baseUrl()}/ping").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = CONNECT_TIMEOUT_MS
            }
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        }.getOrDefault(false)

        val screenFlag = runCatching {
            Settings.Global.getInt(context.contentResolver, "geely_gpt_rear_screen", 0) == 1
        }.getOrDefault(false)

        val updated = RearStatus(
            isEasBusBound = pingOk,
            isRearScreenPowered = pingOk || screenFlag,
            remoteServices = if (pingOk) listOf("rear_agent_http:$REAR_AGENT_PORT") else emptyList()
        )
        _status.value = updated
        updated
    }

    fun queryRemoteAvailableServices(): List<String> = _status.value.remoteServices

    /** Получить список установленных пользовательских приложений на 2-м ряду */
    suspend fun fetchRearPackages(): Result<List<RearInstalledApp>> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("${baseUrl()}/packages").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = 10_000
            }
            try {
                if (conn.responseCode != 200) {
                    throw IllegalStateException("Агент 2-го ряда вернул HTTP ${conn.responseCode}")
                }
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val arr = JSONArray(body)
                val list = mutableListOf<RearInstalledApp>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        RearInstalledApp(
                            packageName = obj.getString("packageName"),
                            label = obj.optString("label", obj.getString("packageName")),
                            versionName = obj.optString("versionName", ""),
                            versionCode = obj.optLong("versionCode", 0L),
                            dpi = obj.optInt("dpi", 0)
                        )
                    )
                }
                list
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Передать APK на 2-й ряд по VLAN eth0.20 и установить его через локальный агент 2-го ряда */
    suspend fun installToRearViaEasBus(
        apkFile: File,
        downloadUrl: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!apkFile.exists()) {
            return@withContext Result.failure(IllegalArgumentException("Файл APK не найден на устройстве"))
        }

        runCatching {
            val encodedName = URLEncoder.encode(apkFile.name, "UTF-8")
            val conn = (URL("${baseUrl()}/install?name=$encodedName").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setFixedLengthStreamingMode(apkFile.length())
                setRequestProperty("Content-Type", "application/vnd.android.package-archive")
                setRequestProperty("X-File-Name", apkFile.name)
            }

            try {
                FileInputStream(apkFile).use { fis ->
                    BufferedOutputStream(conn.outputStream, 64 * 1024).use { bos ->
                        fis.copyTo(bos, 64 * 1024)
                        bos.flush()
                    }
                }

                val code = conn.responseCode
                val responseStream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = responseStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

                if (code !in 200..299) {
                    throw IllegalStateException(
                        body.ifBlank { "Ошибка установки на 2-й ряд (HTTP $code)" }
                    )
                }
                body.ifBlank { "Установлено на 2-й ряд через агент ApkStore" }
            } finally {
                conn.disconnect()
            }
        }.recoverCatching { err ->
            val msg = err.message.orEmpty()
            if (msg.contains("ECONNREFUSED", ignoreCase = true) ||
                msg.contains("timed out", ignoreCase = true) ||
                msg.contains("failed to connect", ignoreCase = true)
            ) {
                throw IllegalStateException(
                    "Агент ApkStore на 2-м ряду ($REAR_AGENT_HOST:$REAR_AGENT_PORT) не отвечает. Установите и запустите ApkStore на заднем экране."
                )
            }
            throw err
        }
    }

    suspend fun uninstallOnRear(packageName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = URLEncoder.encode(packageName, "UTF-8")
            val conn = (URL("${baseUrl()}/uninstall?pkg=$encoded").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = 30_000
            }
            try {
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IllegalStateException(body.ifBlank { "HTTP $code" })
                body.ifBlank { "Удалено на 2-м ряду" }
            } finally {
                conn.disconnect()
            }
        }
    }

    suspend fun setRearAppDpi(packageName: String, dpi: Int): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = URLEncoder.encode(packageName, "UTF-8")
            val conn = (URL("${baseUrl()}/dpi?pkg=$encoded&dpi=$dpi").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = 10_000
            }
            try {
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IllegalStateException(body.ifBlank { "HTTP $code" })
                body.ifBlank { "DPI на 2-м ряду обновлён" }
            } finally {
                conn.disconnect()
            }
        }
    }
}

/**
 * Лёгкий встроенный HTTP-сервер (порт 18999), запускаемый внутри CarSystemService.
 * Когда ApkStore установлен на 2-м ряду, этот сервер принимает команды от переднего ГУ по VLAN eth0.20.
 */
class RearAgentServer(
    private val context: Context,
    private val adbClient: AdbClient,
    private val port: Int = RearDhuEasBridge.REAR_AGENT_PORT
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (serverSocket != null) return
        scope.launch {
            runCatching {
                val ss = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", port))
                }
                serverSocket = ss
                while (isActive && !ss.isClosed) {
                    val client = runCatching { ss.accept() }.getOrNull() ?: continue
                    launch { handleClient(client) }
                }
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 120_000
            val input = BufferedInputStream(s.getInputStream(), 64 * 1024)
            val output = BufferedOutputStream(s.getOutputStream())

            val requestLine = readAsciiLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val rawTarget = parts[1]

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readAsciiLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
            }

            val path = rawTarget.substringBefore('?')
            val queryParams = parseQuery(rawTarget.substringAfter('?', ""))
            val uidUser = (Process.myUid() / 100000).takeIf { it >= 10 } ?: 10

            when {
                method == "GET" && path == "/ping" -> {
                    sendResponse(output, 200, "application/json", """{"status":"ok","user":$uidUser}""")
                }

                method == "GET" && path == "/packages" -> {
                    val json = buildInstalledPackagesJson()
                    sendResponse(output, 200, "application/json", json)
                }

                method == "POST" && path == "/install" -> {
                    val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L
                    if (contentLength <= 0L) {
                        sendResponse(output, 400, "text/plain", "Отсутствует Content-Length")
                        return
                    }
                    val fileName = (queryParams["name"] ?: headers["x-file-name"] ?: "rear_install.apk")
                        .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    val dir = File(context.cacheDir, "rear_incoming").apply { mkdirs() }
                    val apkFile = File(dir, fileName)

                    runCatching {
                        FileOutputStream(apkFile).use { fos ->
                            val buf = ByteArray(64 * 1024)
                            var remaining = contentLength
                            while (remaining > 0) {
                                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                                val read = input.read(buf, 0, toRead)
                                if (read <= 0) break
                                fos.write(buf, 0, read)
                                remaining -= read
                            }
                            fos.flush()
                        }

                        val installRes = FileInputStream(apkFile).use { fis ->
                            adbClient.streamInstallApk(
                                apkStream = fis,
                                totalBytes = apkFile.length(),
                                userId = uidUser,
                                host = "127.0.0.1"
                            )
                        }
                        apkFile.delete()

                        if (installRes.isSuccess) {
                            sendResponse(output, 200, "text/plain", "Установлено на 2-й ряд: $fileName")
                        } else {
                            sendResponse(
                                output,
                                500,
                                "text/plain",
                                installRes.exceptionOrNull()?.message ?: "Ошибка локальной установки на 2-м ряду"
                            )
                        }
                    }.onFailure { e ->
                        apkFile.delete()
                        sendResponse(output, 500, "text/plain", e.message ?: "Ошибка приёма APK")
                    }
                }

                method == "POST" && path == "/uninstall" -> {
                    val pkg = queryParams["pkg"].orEmpty()
                    if (pkg.isBlank()) {
                        sendResponse(output, 400, "text/plain", "Не указан пакет")
                        return
                    }
                    val res = adbClient.shell("pm uninstall --user $uidUser $pkg", host = "127.0.0.1")
                    if (res.isSuccess) {
                        sendResponse(output, 200, "text/plain", res.getOrDefault("Success"))
                    } else {
                        sendResponse(output, 500, "text/plain", res.exceptionOrNull()?.message ?: "Ошибка удаления")
                    }
                }

                method == "POST" && path == "/dpi" -> {
                    val pkg = queryParams["pkg"].orEmpty()
                    val dpi = queryParams["dpi"]?.toIntOrNull() ?: 0
                    if (pkg.isBlank()) {
                        sendResponse(output, 400, "text/plain", "Не указан пакет")
                        return
                    }
                    DpiConfigStore.setDpi(context, pkg, dpi)
                    adbClient.shell("am force-stop --user $uidUser $pkg", host = "127.0.0.1", timeoutMs = 3000L)
                    sendResponse(output, 200, "text/plain", "DPI обновлён: $dpi")
                }

                else -> {
                    sendResponse(output, 404, "text/plain", "Not Found")
                }
            }
        }
    }

    private fun buildInstalledPackagesJson(): String {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
        val arr = JSONArray()
        for (pi in packages) {
            val ai = pi.applicationInfo ?: continue
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSystem && !isUpdatedSystem) continue
            if (pi.packageName == context.packageName) continue

            val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }

            arr.put(JSONObject().apply {
                put("packageName", pi.packageName)
                put("label", pm.getApplicationLabel(ai).toString())
                put("versionName", pi.versionName.orEmpty())
                put("versionCode", vCode)
                put("dpi", DpiConfigStore.getDpi(context, pi.packageName))
            })
        }
        return arr.toString()
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val baos = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (baos.size() == 0) null else baos.toString("UTF-8")
            if (b == '\n'.code) break
            if (b != '\r'.code) baos.write(b)
        }
        return baos.toString("UTF-8")
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        for (pair in query.split("&")) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                map[k] = v
            }
        }
        return map
    }

    private fun sendResponse(out: BufferedOutputStream, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val statusText = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Internal Server Error"
        }
        val header = buildString {
            append("HTTP/1.1 $code $statusText\r\n")
            append("Content-Type: $contentType; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }
}