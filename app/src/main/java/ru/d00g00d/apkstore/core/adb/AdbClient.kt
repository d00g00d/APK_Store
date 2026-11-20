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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
    private val mutexMap = ConcurrentHashMap<String, Mutex>()
    private val localIdGen = AtomicInteger(10)

    private fun mutexFor(host: String) = mutexMap.getOrPut(host) { Mutex() }

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

    suspend fun shell(
        command: String,
        host: String = "127.0.0.1",
        port: Int = 5555,
        timeoutMs: Long = 10_000L
    ): Result<String> = withContext(Dispatchers.IO) {
        mutexFor(host).withLock {
            withTimeoutOrNull(timeoutMs) {
                runCatching {
                    openSession(host, port, 1500) { input, output ->
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
    }

    /** Потоковая передача APK в pm install -S без ограничения размера и без временных файлов */
    suspend fun streamInstallApk(
        apkStream: InputStream,
        totalBytes: Long,
        userId: Int,
        host: String = "127.0.0.1",
        port: Int = 5555,
        onProgress: ((Float) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        mutexFor(host).withLock {
            runCatching {
                openSession(host, port, 3000, soTimeout = 120_000) { input, output ->
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

                        val ack = readPacket(input)
                        if (ack.cmd == A_CLSE) {
                            throw IllegalStateException("adbd closed stream prematurely at $sent/$totalBytes")
                        }
                        sent += read
                        onProgress?.invoke((sent.toFloat() / totalBytes).coerceIn(0f, 1f))
                    }

                    val resultOut = StringBuilder()
                    while (true) {
                        val pkt = readPacket(input)
                        when (pkt.cmd) {
                            A_WRTE -> {
                                resultOut.append(String(pkt.payload, Charsets.UTF_8))
                                sendPacket(output, A_OKAY, localId, remoteId, ByteArray(0))
                            }
                            A_CLSE -> {
                                sendPacket(output, A_CLSE, localId, remoteId, ByteArray(0))
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
    }

    private fun <T> openSession(
        host: String,
        port: Int,
        connectTimeoutMs: Int,
        soTimeout: Int = 15_000,
        block: (DataInputStream, OutputStream) -> T
    ): T {
        val socket = Socket()
        socket.soTimeout = soTimeout
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        return socket.use { s ->
            val input = DataInputStream(s.getInputStream())
            val output = s.getOutputStream()

            sendPacket(output, A_CNXN, ADB_VERSION, MAX_PAYLOAD, "host::apkstore\u0000".toByteArray())
            var resp = readPacket(input)

            if (resp.cmd == A_AUTH && resp.arg0 == AUTH_TOKEN) {
                val signed = crypto.signAdbToken(resp.payload)
                sendPacket(output, A_AUTH, AUTH_SIGNATURE, 0, signed)
                resp = readPacket(input)
                if (resp.cmd == A_AUTH && resp.arg0 == AUTH_TOKEN) {
                    sendPacket(output, A_AUTH, AUTH_RSAPUBLICKEY, 0, crypto.getAdbPublicKeyPayload())
                    resp = readPacket(input)
                }
            }
            if (resp.cmd != A_CNXN) {
                throw SecurityException("ADB unauthorized or handshake failed (cmd=0x${resp.cmd.toString(16)})")
            }
            block(input, output)
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