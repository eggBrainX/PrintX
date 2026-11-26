package com.example.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class ImapEmailSummary(
    val uid: String,
    val subject: String,
    val from: String,
    val date: String,
    val previewBody: String
)

class ImapClient {

    suspend fun fetchRecentEmails(
        host: String,
        port: Int = 993,
        email: String,
        appPassword: String,
        limit: Int = 15
    ): Result<List<ImapEmailSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val socket = SSLSocketFactory.getDefault().createSocket(host.trim(), port) as SSLSocket
            socket.soTimeout = 15_000
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(s.outputStream, Charsets.UTF_8))

                // Greeting
                reader.readLine()

                fun sendCommand(tag: String, cmd: String): List<String> {
                    writer.write("$tag $cmd\r\n")
                    writer.flush()
                    val lines = mutableListOf<String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        lines.add(line)
                        if (line.startsWith("$tag OK", ignoreCase = true)) break
                        if (line.startsWith("$tag NO", ignoreCase = true) || line.startsWith("$tag BAD", ignoreCase = true)) {
                            throw IllegalStateException(line)
                        }
                    }
                    return lines
                }

                val safeEmail = email.replace("\"", "\\\"")
                val safePass = appPassword.replace("\"", "\\\"")
                sendCommand("A1", "LOGIN \"$safeEmail\" \"$safePass\"")
                val selectLines = sendCommand("A2", "SELECT INBOX")
                val existsCount = selectLines
                    .firstOrNull { it.contains("EXISTS", ignoreCase = true) }
                    ?.trim()
                    ?.substringAfter("* ")
                    ?.substringBefore(" EXISTS")
                    ?.toIntOrNull() ?: 0

                if (existsCount <= 0) {
                    sendCommand("A4", "LOGOUT")
                    return@runCatching emptyList()
                }

                val startSeq = (existsCount - limit + 1).coerceAtLeast(1)
                val fetchLines = sendCommand("A3", "FETCH $startSeq:$existsCount (BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE)] BODY.PEEK[TEXT]<0.1200>)")

                sendCommand("A4", "LOGOUT")
                parseImapFetch(fetchLines).reversed()
            }
        }
    }

    private fun parseImapFetch(lines: List<String>): List<ImapEmailSummary> {
        val result = mutableListOf<ImapEmailSummary>()
        var currentSeq = ""
        var subject = "(Tanpa Subjek)"
        var from = ""
        var date = ""
        val bodyBuf = StringBuilder()
        var inBodyText = false

        fun flushCurrent() {
            if (currentSeq.isNotEmpty()) {
                result.add(
                    ImapEmailSummary(
                        uid = currentSeq,
                        subject = subject,
                        from = from,
                        date = date,
                        previewBody = bodyBuf.toString().trim()
                    )
                )
            }
        }

        lines.forEach { raw ->
            when {
                raw.startsWith("* ") && raw.contains("FETCH") -> {
                    flushCurrent()
                    currentSeq = raw.substringAfter("* ").substringBefore(" FETCH").trim()
                    subject = "(Tanpa Subjek)"
                    from = ""
                    date = ""
                    bodyBuf.clear()
                    inBodyText = false
                }
                raw.startsWith("Subject:", ignoreCase = true) -> {
                    subject = raw.substringAfter(":").trim().ifBlank { "(Tanpa Subjek)" }
                }
                raw.startsWith("From:", ignoreCase = true) -> {
                    from = raw.substringAfter(":").trim()
                }
                raw.startsWith("Date:", ignoreCase = true) -> {
                    date = raw.substringAfter(":").trim()
                }
                raw.contains("BODY[TEXT]") -> {
                    inBodyText = true
                }
                raw == ")" -> {
                    inBodyText = false
                }
                inBodyText -> {
                    bodyBuf.appendLine(raw)
                }
            }
        }
        flushCurrent()
        return result
    }
}
