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
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Properties

class SmbAuthRequiredException(
    val host: String,
    val detailMessage: String = "PC Windows $host membutuhkan login Windows (Username & Password) untuk melihat printer share."
) : SecurityException(detailMessage)

/**
 * Windows SMB Printer Sharing client powered by jCIFS-ng (supports SMB2/SMB3 & SMB1 fallback).
 */
class SmbPrinterClient {

    internal fun buildContext(
        username: String? = null,
        password: String? = null,
        domain: String? = null,
        useSmb2Only: Boolean = true
    ): CIFSContext {
        val props = Properties().apply {
            if (useSmb2Only) {
                setProperty("jcifs.smb.client.minVersion", "SMB202")
                setProperty("jcifs.smb.client.maxVersion", "SMB311")
            } else {
                setProperty("jcifs.smb.client.minVersion", "SMB1")
                setProperty("jcifs.smb.client.maxVersion", "SMB311")
            }
            setProperty("jcifs.smb.client.useSMB2Negotiation", "true")
            setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
            setProperty("jcifs.smb.client.signingPreferred", "false")
            setProperty("jcifs.smb.client.dfs.disabled", "true")
            setProperty("jcifs.smb.client.connTimeout", "4000")
            setProperty("jcifs.smb.client.responseTimeout", "6000")
            setProperty("jcifs.smb.client.soTimeout", "7000")
            setProperty("jcifs.resolveOrder", "BCAST,DNS")
        }
        val base = BaseContext(PropertyConfiguration(props))
        val cleanUser = username?.trim().orEmpty()
        return if (cleanUser.isNotEmpty()) {
            // Support "DOMAIN\user" or "PC\user" syntax automatically
            val parsedDomain: String?
            val parsedUser: String
            if (cleanUser.contains('\\')) {
                parsedDomain = cleanUser.substringBefore('\\').ifBlank { null }
                parsedUser = cleanUser.substringAfter('\\')
            } else {
                parsedDomain = domain?.trim()?.ifBlank { null }
                parsedUser = cleanUser
            }
            base.withCredentials(
                NtlmPasswordAuthenticator(
                    parsedDomain,
                    parsedUser,
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
            val cleanUser = username?.trim().orEmpty()
            val hasExplicitCreds = cleanUser.isNotEmpty()

            val shares = try {
                val ctx = buildContext(cleanUser.ifEmpty { null }, password, domain, useSmb2Only = true)
                SmbFile("smb://$cleanHost/", ctx).listFiles() ?: emptyArray()
            } catch (authEx: SmbAuthException) {
                if (hasExplicitCreds && domain.isNullOrBlank() && !cleanUser.contains('\\')) {
                    // Retry with host/computer name as NTLM domain, or Guest if Windows allows guest
                    try {
                        val hostDomainCtx = buildContext(cleanUser, password, cleanHost, useSmb2Only = true)
                        SmbFile("smb://$cleanHost/", hostDomainCtx).listFiles() ?: emptyArray()
                    } catch (retryAuthEx: SmbAuthException) {
                        throw SmbAuthRequiredException(cleanHost, formatSmbAuthError(cleanHost, retryAuthEx, hasExplicitCreds))
                    }
                } else {
                    throw SmbAuthRequiredException(cleanHost, formatSmbAuthError(cleanHost, authEx, hasExplicitCreds))
                }
            } catch (smbEx: SmbException) {
                // Fallback to SMB1/SMB2 multi-dialect negotiation if pure SMB2 failed
                try {
                    val fallbackCtx = buildContext(cleanUser.ifEmpty { null }, password, domain, useSmb2Only = false)
                    SmbFile("smb://$cleanHost/", fallbackCtx).listFiles() ?: emptyArray()
                } catch (authEx: SmbAuthException) {
                    throw SmbAuthRequiredException(cleanHost, formatSmbAuthError(cleanHost, authEx, hasExplicitCreds))
                } catch (fallbackEx: Exception) {
                    throw IllegalStateException(
                        "Tidak bisa terhubung ke SMB $cleanHost: ${smbEx.localizedMessage ?: fallbackEx.localizedMessage}"
                    )
                }
            }

            shares.mapNotNull { share ->
                val rawName = share.name.trimEnd('/')
                if (rawName.endsWith("$") || rawName.equals("IPC", true)) {
                    return@mapNotNull null
                }
                val shareType = runCatching { share.type }.getOrDefault(0)
                val isPrinterShare = (shareType and SmbConstants.TYPE_PRINTER) == SmbConstants.TYPE_PRINTER ||
                    shareType == SmbConstants.TYPE_PRINTER
                if (!isPrinterShare) {
                    return@mapNotNull null
                }
                buildSmbPrinterDevice(
                    host = cleanHost,
                    shareName = rawName,
                    username = cleanUser.ifBlank { null },
                    password = password?.takeIf { cleanUser.isNotBlank() },
                    domain = domain?.trim()?.ifBlank { null }
                )
            }
        }
    }

    fun buildSmbPrinterDevice(
        host: String,
        shareName: String,
        username: String?,
        password: String?,
        domain: String?,
        customName: String? = null
    ): PrinterDevice {
        val cleanHost = host.trim()
        val cleanShare = shareName.trim().trimStart('/').trimEnd('/')
        return PrinterDevice(
            id = "SMB_${cleanHost}_$cleanShare",
            name = customName?.takeIf { it.isNotBlank() } ?: "$cleanShare (Windows)",
            host = cleanHost,
            port = 445,
            path = "/$cleanShare",
            protocol = PrinterProtocol.SMB_WINDOWS,
            smbShareName = cleanShare,
            smbUsername = username?.trim()?.ifBlank { null },
            smbPassword = password?.takeIf { !username.isNullOrBlank() },
            smbDomain = domain?.trim()?.ifBlank { null },
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

    private fun formatSmbAuthError(host: String, ex: SmbAuthException, hasCreds: Boolean): String {
        val statusHex = "0x" + Integer.toHexString(ex.ntStatus).uppercase()
        if (!hasCreds) {
            return "PC Windows $host membutuhkan login Windows (Username & Password) untuk melihat printer share."
        }
        return when (ex.ntStatus) {
            0xC000006D.toInt(), 0xC000006A.toInt() ->
                "Username atau Password Windows salah ($statusHex). Catatan: jika login Windows memakai PIN/Akun Microsoft, masukkan email & password Akun Microsoft atau buat user lokal di Windows."
            0xC0000022.toInt() ->
                "Windows menolak daftar share ($statusHex - Access Denied). Isi Nama Share Printer secara manual di bawah untuk terhubung langsung."
            0xC0000072.toInt() ->
                "Akun Windows dinonaktifkan ($statusHex). Gunakan akun Windows lain yang aktif."
            else ->
                "Login SMB ke $host ditolak: ${ex.localizedMessage ?: statusHex} ($statusHex). Coba masukkan Nama Share Printer langsung di bawah."
        }
    }

    suspend fun checkShareOnline(printer: PrinterDevice): Boolean = withContext(Dispatchers.IO) {
        val cleanHost = printer.host.trim()
        val tcpOk = runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(cleanHost, 445), 1800)
                true
            }
        }.getOrDefault(false)
        if (!tcpOk) return@withContext false

        val shareName = (printer.smbShareName ?: printer.path.trimStart('/')).trimStart('/')
        if (shareName.isBlank()) return@withContext true

        // Check via share enumeration first; note Windows PRINTQ shares often reject File.exists()
        val listed = discoverPrintersOnHost(cleanHost, printer.smbUsername, printer.smbPassword, printer.smbDomain)
            .getOrNull()
        if (listed != null && listed.any { it.smbShareName.equals(shareName, ignoreCase = true) }) {
            return@withContext true
        }

        // Fallback: probe share node type without triggering an empty print spool job
        runCatching {
            val ctx = buildContext(printer.smbUsername, printer.smbPassword, printer.smbDomain, useSmb2Only = true)
            val sf = SmbFile("smb://$cleanHost/$shareName/", ctx)
            sf.type == SmbConstants.TYPE_PRINTER
        }.getOrDefault(tcpOk)
    }

    suspend fun sendPrintJob(
        printer: PrinterDevice,
        file: File,
        copies: Int,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanHost = printer.host.trim()
            val shareName = (printer.smbShareName ?: printer.path.trimStart('/')).trimStart('/').trimEnd('/')
            if (shareName.isBlank()) {
                throw IllegalArgumentException("Nama Share Printer Windows tidak boleh kosong")
            }
            val totalBytes = (file.length() * copies.coerceAtLeast(1)).coerceAtLeast(1L)
            var sentTotal = 0L

            suspend fun writeWithCtx(ctx: CIFSContext) {
                repeat(copies.coerceIn(1, 50)) { idx ->
                    val safeName = "lanprint_${System.currentTimeMillis()}_${idx + 1}.${file.extension.ifBlank { "pdf" }}"
                    // On SMB2/3 Windows Print Spooler accepts a file create inside \\host\PrinterShare\job.pdf
                    val spoolUrl = "smb://$cleanHost/$shareName/$safeName"
                    val rootShareUrl = "smb://$cleanHost/$shareName"
                    val out = try {
                        SmbFile(spoolUrl, ctx).openOutputStream()
                    } catch (_: Exception) {
                        SmbFile(rootShareUrl, ctx).openOutputStream()
                    }

                    out.use { stream ->
                        FileInputStream(file).use { inp ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val read = inp.read(buffer)
                                if (read <= 0) break
                                stream.write(buffer, 0, read)
                                sentTotal += read
                                onProgress((sentTotal.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                        stream.flush()
                    }
                }
            }

            try {
                val ctx = buildContext(printer.smbUsername, printer.smbPassword, printer.smbDomain, useSmb2Only = true)
                writeWithCtx(ctx)
            } catch (authEx: SmbAuthException) {
                if (!printer.smbUsername.isNullOrBlank() && printer.smbDomain.isNullOrBlank()) {
                    val hostDomainCtx = buildContext(printer.smbUsername, printer.smbPassword, cleanHost, useSmb2Only = true)
                    sentTotal = 0L
                    writeWithCtx(hostDomainCtx)
                } else {
                    throw SmbAuthRequiredException(
                        cleanHost,
                        formatSmbAuthError(cleanHost, authEx, !printer.smbUsername.isNullOrBlank())
                    )
                }
            } catch (_: SmbException) {
                val fallbackCtx = buildContext(printer.smbUsername, printer.smbPassword, printer.smbDomain, useSmb2Only = false)
                sentTotal = 0L
                writeWithCtx(fallbackCtx)
            }
        }
    }
}
