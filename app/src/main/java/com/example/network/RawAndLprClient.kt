package com.example.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.net.Socket

class RawAndLprClient {

    suspend fun probePort(host: String, port: Int, timeoutMs: Int = 1800): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        }.getOrDefault(false)
    }

    suspend fun sendRaw9100(
        host: String,
        port: Int = 9100,
        file: File,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val total = file.length().coerceAtLeast(1L)
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 8000)
                socket.soTimeout = 30_000
                val out = socket.getOutputStream()
                FileInputStream(file).use { input ->
                    val buffer = ByteArray(16 * 1024)
                    var sent = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        sent += read
                        onProgress((sent.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                    }
                }
                out.flush()
            }
        }
    }

    suspend fun sendLpr515(
        host: String,
        port: Int = 515,
        queueName: String = "lp",
        jobName: String,
        file: File,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanQueue = queueName.ifBlank { "lp" }
            val safeJobName = jobName.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(32)
            val controlContent = "Hlanprint\nPlanprint\nJ$safeJobName\nldfA001lanprint\nUdfA001lanprint\nN$safeJobName\n"
            val controlBytes = controlContent.toByteArray(Charsets.US_ASCII)
            val dataLen = file.length()

            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 8000)
                socket.soTimeout = 30_000
                val out = socket.getOutputStream()
                val inp = socket.getInputStream()

                fun expectAck(step: String) {
                    val ack = inp.read()
                    if (ack != 0) throw IllegalStateException("LPR NACK pada $step (code=$ack)")
                }

                // 1. Receive printer job
                out.write("\u0002$cleanQueue\n".toByteArray(Charsets.US_ASCII))
                out.flush()
                expectAck("receive-job")

                // 2. Receive control file
                out.write("\u0002${controlBytes.size} cfA001lanprint\n".toByteArray(Charsets.US_ASCII))
                out.flush()
                expectAck("control-header")

                out.write(controlBytes)
                out.write(0)
                out.flush()
                expectAck("control-body")

                // 3. Receive data file
                out.write("\u0003$dataLen dfA001lanprint\n".toByteArray(Charsets.US_ASCII))
                out.flush()
                expectAck("data-header")

                val total = dataLen.coerceAtLeast(1L)
                var sent = 0L
                FileInputStream(file).use { fis ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val read = fis.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        sent += read
                        onProgress((sent.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                    }
                }
                out.write(0)
                out.flush()
                expectAck("data-body")
            }
        }
    }
}
