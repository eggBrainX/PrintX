package com.example.domain.model

import com.squareup.moshi.JsonClass

enum class PrinterProtocol(val label: String, val defaultPort: Int, val isViaLaptop: Boolean) {
    IPP("IPP (LAN)", 631, false),
    IPPS("IPPS (TLS)", 631, false),
    RAW_9100("RAW 9100", 9100, false),
    LPR_515("LPR / LPD", 515, false),
    SMB_WINDOWS("Windows SMB Share", 445, false),
    USB_OTG("USB OTG Langsung", 0, false),
    COMPANION_HTTP("USB via Laptop (HTTP)", 8765, true),
    COMPANION_HTTPS("USB via Laptop (HTTPS)", 8765, true)
}

@JsonClass(generateAdapter = true)
data class InkSupplyLevel(
    val name: String,
    val colorHex: String,
    val levelPercent: Int, // 0..100, or -1 if unknown
    val type: String = "ink"
)

@JsonClass(generateAdapter = true)
data class PrinterCapabilities(
    val supportsColor: Boolean = true,
    val supportsDuplex: Boolean = false,
    val supportedPaperIds: List<String> = listOf("A4", "F4", "LETTER", "A3", "A5"),
    val supportedMimeTypes: List<String> = listOf("application/pdf", "image/jpeg"),
    val supportedTrays: List<String> = listOf("auto", "tray-1"),
    val supportedFinishings: List<String> = emptyList(),
    val supportedDpis: List<Int> = listOf(150, 300, 600),
    val supportedOutputBins: List<String> = listOf("auto"),
    val inkSupplies: List<InkSupplyLevel> = emptyList(),
    val readyMedia: List<String> = emptyList(),
    val stateReasons: List<String> = emptyList(),
    val makeAndModel: String = "",
    val printerStateMessage: String = "Siap",
    val attributesProbed: Boolean = false,
    val lastProbedAt: Long = 0L
) {
    fun supportsPaper(paper: PaperSize): Boolean {
        if (!attributesProbed || supportedPaperIds.isEmpty()) return true
        return supportedPaperIds.any { it.equals(paper.id, ignoreCase = true) }
    }
}

@JsonClass(generateAdapter = true)
data class PrinterDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val path: String = "/ipp/print",
    val protocol: PrinterProtocol,
    val companionPrinterName: String? = null,
    val pairingToken: String? = null,
    val tlsCertFingerprint: String? = null,
    val smbShareName: String? = null,
    val smbUsername: String? = null,
    val smbPassword: String? = null,
    val smbDomain: String? = null,
    val usbDeviceName: String? = null,
    val usbVendorId: Int? = null,
    val usbProductId: Int? = null,
    val capabilities: PrinterCapabilities = PrinterCapabilities(),
    val isSaved: Boolean = false,
    val isDefault: Boolean = false,
    val isOnline: Boolean = true,
    val lastUsedAt: Long = 0L
) {
    val endpointLabel: String
        get() = when (protocol) {
            PrinterProtocol.COMPANION_HTTP, PrinterProtocol.COMPANION_HTTPS ->
                "${companionPrinterName ?: name} @ $host:$port"
            PrinterProtocol.IPP, PrinterProtocol.IPPS ->
                "${protocol.name.lowercase()}://$host:$port$path"
            PrinterProtocol.SMB_WINDOWS ->
                "smb://$host/${smbShareName ?: path.trimStart('/')}"
            PrinterProtocol.USB_OTG ->
                "USB OTG (${usbDeviceName ?: host})"
            else -> "$host:$port (${protocol.label})"
        }

    val ippHttpUrl: String
        get() {
            val scheme = if (protocol == PrinterProtocol.IPPS) "https" else "http"
            val cleanPath = if (path.startsWith("/")) path else "/$path"
            return "$scheme://$host:$port$cleanPath"
        }

    val companionBaseUrl: String
        get() {
            val scheme = if (protocol == PrinterProtocol.COMPANION_HTTPS) "https" else "http"
            return "$scheme://$host:$port"
        }
}
