package ru.d00g00d.apkstore.core.adb

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import timber.log.Timber

class AdbClient(context: Context) {

    companion object {
        private const val A_CNXN = 0x4e584e43
        private const val A_AUTH = 0x48545541
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_CLSE = 0x45534c43
        private const val A_WRTE = 0x45545257

        private const val ADB_VERSION = 0x01000000
        private const val MAX_PAYLOAD = 256 * 1024

        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3
    }

    private val crypto = AdbCrypto(context.applicationContext)
    private val mutex = Mutex()
    private val localIdGen = AtomicInteger(10)

    private var activeSocket: Socket? = null
    private var activeInput: DataInputStream? = null
    private var activeOutput: OutputStream? = null

    suspend fun isPortOpen(host: String, port: Int = 5555, timeoutMs: Int = 800): Boolean =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), timeoutMs)
                    true
                }
            } catch (_: Exception) {
                false
            }
        }

    /** Устанавливает или переиспользует постоянное соединение с ADB, предотвращая лишние запросы авторизации */
    private suspend fun <T> useSession(
        host: String,
        port: Int,
        block: suspend (DataInputStream, OutputStream) -> T
    ): T = mutex.withLock {
        var input = activeInput
        var output = activeOutput
        val socket = activeSocket

        if (socket == null || socket.isClosed || !socket.isConnected || input == null || output == null) {
            Timber.d("Opening new ADB connection to $host:$port")
            runCatching { activeSocket?.close() }
            
            val newSocket = Socket()
            newSocket.soTimeout = 120_000 // 2 minutes
            newSocket.connect(InetSocketAddress(host, port), 1500)
            
            val inStream = DataInputStream(newSocket.getInputStream())
            val outStream = newSocket.getOutputStream()

            sendPacket(outStream, A_CNXN, ADB_VERSION, MAX_PAYLOAD, "host::apkstore\u0000".toByteArray())
            var resp = readPacket(inStream)

            if (resp.cmd == A_AUTH && resp.arg0 == AUTH_TOKEN) {
                val signed = crypto.signAdbToken(resp.payload)
                sendPacket(outStream, A_AUTH, AUTH_SIGNATURE, 0, signed)
                resp = readPacket(inStream)
                if (resp.cmd == A_AUTH && resp.arg0 == AUTH_TOKEN) {
                    sendPacket(outStream, A_AUTH, AUTH_RSAPUBLICKEY, 0, crypto.getAdbPublicKeyPayload())
                    resp = readPacket(inStream)
                }
            }
            if (resp.cmd != A_CNXN) {
                newSocket.close()
                throw SecurityException("ADB unauthorized or handshake failed (cmd=0x${resp.cmd.toString(16)})")
            }

            activeSocket = newSocket
            activeInput = inStream
            activeOutput = outStream
            
            input = inStream
            output = outStream
            Timber.d("ADB connection established")
        }

        try {
            block(input, output)
        } catch (e: Exception) {
            Timber.e(e, "ADB session error, closing socket")
            runCatching { activeSocket?.close() }
            activeSocket = null
            activeInput = null
            activeOutput = null
            throw e
        }
    }

    suspend fun shell(
        command: String,
        host: String = "127.0.0.1",
        port: Int = 5555,
        timeoutMs: Long = 10_000L
    ): Result<String> = withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMs) {
            runCatching {
                useSession(host, port) { input, output ->
                    val localId = localIdGen.incrementAndGet()
                    sendPacket(output, A_OPEN, localId, 0, "shell:$command\u0000".toByteArray())

                    var remoteId = 0
                    val sb = StringBuilder()
                    while (true) {
                        val pkt = readPacket(input)
                        when (pkt.cmd) {
                            A_OKAY -> if (remoteId == 0) remoteId = pkt.arg0
                            A_WRTE -> {
                                remoteId = pkt.arg0
                                sb.append(String(pkt.payload, Charsets.UTF_8))
                                sendPacket(output, A_OKAY, localId, remoteId, ByteArray(0))
                            }
                            A_CLSE -> {
                                sendPacket(output, A_CLSE, localId, pkt.arg0, ByteArray(0))
                                break
                            }
                        }
                    }
                    sb.toString().trim()
                }
            }
        } ?: Result.failure(IllegalStateException("ADB timeout ($timeoutMs ms) for $host: $command"))
    }

    suspend fun streamInstallApk(
        apkStream: InputStream,
        totalBytes: Long,
        userId: Int,
        host: String = "127.0.0.1",
        port: Int = 5555,
        onProgress: ((Float) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            useSession(host, port) { input, output ->
                val localId = localIdGen.incrementAndGet()
                val service = "exec:pm install -r -g --user $userId -S $totalBytes\u0000"
                sendPacket(output, A_OPEN, localId, 0, service.toByteArray())

                val openReply = readPacket(input)
                if (openReply.cmd != A_OKAY) {
                    throw IllegalStateException("Expected A_OKAY on pm install, got ${openReply.cmd}")
                }
                val remoteId = openReply.arg0

                val buffer = ByteArray(32 * 1024)
                var sent = 0L
                while (true) {
                    val read = apkStream.read(buffer)
                    if (read <= 0) break
                    val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                    sendPacket(output, A_WRTE, localId, remoteId, chunk)

                    // Чтение A_OKAY на пакет, но мы не блокируемся на нем для ускорения (или блокируемся если adb требует)
                    // Для потокового pm install adb обычно отвечает ОК на пакеты, но это замедляет. 
                    // Мы будем отправлять и изредка проверять закрытие.
                    if (input.available() > 0) {
                        val ack = readPacket(input)
                        if (ack.cmd == A_CLSE) {
                            throw IllegalStateException("adbd closed stream prematurely at $sent/$totalBytes")
                        }
                    }
                    sent += read
                    onProgress?.invoke((sent.toFloat() / totalBytes).coerceIn(0f, 1f))
                }

                // Закрываем стрим с нашей стороны (мы закончили писать)
                sendPacket(output, A_CLSE, localId, remoteId, ByteArray(0))

                // Ждем финальный вывод pm
                val resultOut = StringBuilder()
                while (true) {
                    val pkt = readPacket(input)
                    when (pkt.cmd) {
                        A_WRTE -> {
                            resultOut.append(String(pkt.payload, Charsets.UTF_8))
                            sendPacket(output, A_OKAY, localId, pkt.arg0, ByteArray(0))
                        }
                        A_CLSE -> {
                            // Ок, сервер закрыл тоже
                            break
                        }
                    }
                }
                val res = resultOut.toString().trim()
                if (!res.contains("Success", ignoreCase = true)) {
                    throw IllegalStateException("Install failed: $res")
                }
                res
            }
        }
    }

    private fun sendPacket(out: OutputStream, cmd: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        var checksum = 0
        for (b in payload) checksum += (b.toInt() and 0xFF)
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(cmd)
            putInt(arg0)
            putInt(arg1)
            putInt(payload.size)
            putInt(checksum)
            putInt(cmd xor -1)
        }
        out.write(header.array())
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    private data class AdbPacket(val cmd: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    private fun readPacket(input: DataInputStream): AdbPacket {
        val hdrBytes = ByteArray(24)
        input.readFully(hdrBytes)
        val hdr = ByteBuffer.wrap(hdrBytes).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = hdr.int
        val arg0 = hdr.int
        val arg1 = hdr.int
        val len = hdr.int
        hdr.int
        hdr.int
        val payload = if (len > 0) ByteArray(len).also { input.readFully(it) } else ByteArray(0)
        return AdbPacket(cmd, arg0, arg1, payload)
    }
}