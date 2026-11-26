package com.example.network

import com.example.domain.model.PrinterCapabilities
import com.example.domain.model.PrinterDevice
import com.example.domain.model.PrinterProtocol
import jcifs.CIFSContext
import jcifs.SmbConstants
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.Properties

class SmbAuthRequiredException(val host: String) :
    SecurityException("PC Windows $host membutuhkan login Windows (Username & Password) untuk melihat printer share.")

/**
 * Windows SMB Printer Sharing client powered by jCIFS-ng (supports SMB1, SMB2 & SMB3).
 */
class SmbPrinterClient {

    private fun buildContext(
        username: String? = null,
        password: String? = null,
        domain: String? = null
    ): CIFSContext {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.enableSMB2", "true")
            setProperty("jcifs.smb.client.useSMB2Negotiation", "true")
            setProperty("jcifs.smb.client.minVersion", "SMB1")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.connTimeout", "3500")
            setProperty("jcifs.smb.client.responseTimeout", "5000")
            setProperty("jcifs.smb.client.soTimeout", "6000")
            setProperty("jcifs.resolveOrder", "BCAST,DNS")
        }
        val base = BaseContext(PropertyConfiguration(props))
        return if (!username.isNullOrBlank()) {
            base.withCredentials(
                NtlmPasswordAuthenticator(
                    domain?.trim().orEmpty(),
                    username.trim(),
                    password.orEmpty()
                )
            )
        } else {
            base.withAnonymousCredentials()
        }
    }

    suspend fun discoverPrintersOnHost(
        host: String,
        username: String? = null,
        password: String? = null,
        domain: String? = null
    ): Result<List<PrinterDevice>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanHost = host.trim()
            val ctx = buildContext(username, password, domain)
            val root = SmbFile("smb://$cleanHost/", ctx)
            val shares = try {
                root.listFiles() ?: emptyArray()
            } catch (authEx: SmbAuthException) {
                throw SmbAuthRequiredException(cleanHost)
            }

            shares.mapNotNull { share ->
                val rawName = share.name.trimEnd('/')
                if (rawName.endsWith("$") || rawName.equals("IPC", true)) {
                    return@mapNotNull null
                }
                val shareType = share.type
                val isPrinterShare = (shareType and SmbConstants.TYPE_PRINTER) == SmbConstants.TYPE_PRINTER ||
                    shareType == SmbConstants.TYPE_PRINTER
                if (!isPrinterShare) {
                    return@mapNotNull null
                }
                PrinterDevice(
                    id = "SMB_${cleanHost}_$rawName",
                    name = "$rawName (Windows)",
                    host = cleanHost,
                    port = 445,
                    path = "/$rawName",
                    protocol = PrinterProtocol.SMB_WINDOWS,
                    smbShareName = rawName,
                    smbUsername = username?.takeIf { it.isNotBlank() },
                    smbPassword = password?.takeIf { !username.isNullOrBlank() },
                    smbDomain = domain?.takeIf { it.isNotBlank() },
                    capabilities = PrinterCapabilities(
                        supportsColor = true,
                        supportsDuplex = false,
                        supportedMimeTypes = listOf("application/pdf", "application/postscript"),
                        makeAndModel = "Windows Shared Printer @ $cleanHost",
                        printerStateMessage = "Siap via SMB Windows",
                        attributesProbed = true,
                        lastProbedAt = System.currentTimeMillis()
                    ),
                    isOnline = true
                )
            }
        }
    }

    suspend fun browseWorkgroupHosts(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val ctx = buildContext()
            val hosts = mutableListOf<String>()
            val workgroups = SmbFile("smb://", ctx).listFiles() ?: emptyArray()
            for (wg in workgroups) {
                val servers = runCatching { wg.listFiles() }.getOrNull() ?: continue
                for (srv in servers) {
                    val serverName = srv.name.trimEnd('/')
                    if (serverName.isNotBlank()) hosts += serverName
                }
            }
            hosts.distinct()
        }.getOrDefault(emptyList())
    }

    suspend fun checkShareOnline(printer: PrinterDevice): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val shareName = printer.smbShareName ?: printer.path.trimStart('/')
            val ctx = buildContext(printer.smbUsername, printer.smbPassword, printer.smbDomain)
            val smbFile = SmbFile("smb://${printer.host}/$shareName", ctx)
            smbFile.exists()
        }.getOrDefault(false)
    }

    suspend fun sendPrintJob(
        printer: PrinterDevice,
        file: File,
        copies: Int,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val shareName = (printer.smbShareName ?: printer.path.trimStart('/')).trimStart('/')
            if (shareName.isBlank()) {
                throw IllegalArgumentException("Nama Share Printer Windows tidak boleh kosong")
            }
            val ctx = buildContext(printer.smbUsername, printer.smbPassword, printer.smbDomain)
            val shareUrl = "smb://${printer.host}/$shareName"
            val totalBytes = (file.length() * copies.coerceAtLeast(1)).coerceAtLeast(1L)
            var sentTotal = 0L

            repeat(copies.coerceIn(1, 50)) {
                val target = SmbFile(shareUrl, ctx)
                target.openOutputStream().use { out ->
                    FileInputStream(file).use { inp ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = inp.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            sentTotal += read
                            onProgress((sentTotal.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                    out.flush()
                }
            }
        }
    }
}
