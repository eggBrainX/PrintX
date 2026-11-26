package com.example.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.domain.model.PrinterCapabilities
import com.example.domain.model.PrinterDevice
import com.example.domain.model.PrinterProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

data class UsbPrinterInfo(
    val device: UsbDevice,
    val printerInterface: UsbInterface,
    val outEndpoint: UsbEndpoint,
    val inEndpoint: UsbEndpoint?,
    val hasPermission: Boolean,
    val manufacturer: String,
    val productName: String,
    val ieee1284Id: String,
    val commandSets: List<String>
) {
    val displayName: String
        get() = listOf(manufacturer, productName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Printer USB (${device.vendorId}:${device.productId})" }

    fun toPrinterDevice(): PrinterDevice {
        val mimes = buildList {
            if (commandSets.any { it.contains("PDF", true) }) add("application/pdf")
            if (commandSets.any { it.contains("PS", true) || it.contains("POSTSCRIPT", true) }) add("application/postscript")
            if (commandSets.any { it.contains("ESC", true) }) add("application/vnd.escpos")
            if (isEmpty()) add("application/pdf")
        }
        val stateMsg = if (hasPermission) {
            if (commandSets.isNotEmpty()) "USB Siap (${commandSets.take(3).joinToString(", ")})" else "USB OTG Siap"
        } else {
            "Perlu Izin Akses USB"
        }
        return PrinterDevice(
            id = "USB_${device.vendorId}_${device.productId}_${device.deviceId}",
            name = displayName,
            host = device.deviceName,
            port = 0,
            path = "",
            protocol = PrinterProtocol.USB_OTG,
            usbDeviceName = device.deviceName,
            usbVendorId = device.vendorId,
            usbProductId = device.productId,
            capabilities = PrinterCapabilities(
                supportsColor = !commandSets.any { it.contains("ESCPOS", true) },
                supportsDuplex = false,
                supportedMimeTypes = mimes,
                makeAndModel = displayName,
                printerStateMessage = stateMsg,
                attributesProbed = hasPermission,
                lastProbedAt = System.currentTimeMillis()
            ),
            isOnline = true
        )
    }
}

class UsbPrinterManager(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _connectedUsbPrinters = MutableStateFlow<List<UsbPrinterInfo>>(emptyList())
    val connectedUsbPrinters: StateFlow<List<UsbPrinterInfo>> = _connectedUsbPrinters.asStateFlow()

    private val pendingPermissions = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    device?.let { dev ->
                        pendingPermissions.remove(dev.deviceId)?.complete(granted)
                    }
                    refreshConnectedPrinters()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    refreshConnectedPrinters()
                }
            }
        }
    }

    init {
        registerReceivers()
        refreshConnectedPrinters()
    }

    private fun registerReceivers() {
        runCatching {
            val permFilter = IntentFilter(ACTION_USB_PERMISSION)
            ContextCompat.registerReceiver(
                context,
                usbReceiver,
                permFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            val hwFilter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(
                context,
                usbReceiver,
                hwFilter,
                ContextCompat.RECEIVER_EXPORTED
            )
        }
    }

    fun refreshConnectedPrinters(): List<UsbPrinterInfo> {
        val list = mutableListOf<UsbPrinterInfo>()
        usbManager.deviceList.values.forEach { device ->
            val match = findPrinterInterfaceAndEndpoints(device) ?: return@forEach
            val (iface, outEp, inEp) = match
            val hasPerm = usbManager.hasPermission(device)
            var mfg = device.manufacturerName.orEmpty()
            var prod = device.productName.orEmpty()
            var raw1284 = ""
            var cmdSets = emptyList<String>()

            if (hasPerm) {
                raw1284 = readIeee1284DeviceId(device, iface)
                if (raw1284.isNotBlank()) {
                    val parsed = parseIeee1284Id(raw1284)
                    if (mfg.isBlank()) mfg = parsed["MFG"] ?: parsed["MANUFACTURER"].orEmpty()
                    if (prod.isBlank()) prod = parsed["MDL"] ?: parsed["MODEL"].orEmpty()
                    val cmdRaw = parsed["CMD"] ?: parsed["COMMAND SET"].orEmpty()
                    if (cmdRaw.isNotBlank()) {
                        cmdSets = cmdRaw.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
                    }
                }
            }

            list += UsbPrinterInfo(
                device = device,
                printerInterface = iface,
                outEndpoint = outEp,
                inEndpoint = inEp,
                hasPermission = hasPerm,
                manufacturer = mfg,
                productName = prod,
                ieee1284Id = raw1284,
                commandSets = cmdSets
            )
        }
        _connectedUsbPrinters.value = list
        return list
    }

    suspend fun requestPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        val deferred = CompletableDeferred<Boolean>()
        pendingPermissions[device.deviceId] = deferred

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val pi = PendingIntent.getBroadcast(context, device.deviceId, intent, flags)
        usbManager.requestPermission(device, pi)

        val granted = withTimeoutOrNull(30_000L) { deferred.await() } ?: false
        pendingPermissions.remove(device.deviceId)
        refreshConnectedPrinters()
        return granted
    }

    fun findMatchingUsbPrinter(printer: PrinterDevice): UsbPrinterInfo? {
        val current = refreshConnectedPrinters()
        return current.firstOrNull { info ->
            (printer.usbVendorId != null && printer.usbProductId != null &&
                info.device.vendorId == printer.usbVendorId &&
                info.device.productId == printer.usbProductId) ||
                info.device.deviceName == printer.usbDeviceName
        } ?: current.firstOrNull()
    }

    fun readPortStatusLabel(info: UsbPrinterInfo): String {
        if (!usbManager.hasPermission(info.device)) return "Perlu Izin USB"
        val conn = usbManager.openDevice(info.device) ?: return "USB Terpasang"
        return try {
            val buf = ByteArray(1)
            // USB Printer Class GET_PORT_STATUS = 0x01
            val read = conn.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_CLASS or 0x01,
                1,
                0,
                info.printerInterface.id,
                buf,
                1,
                1000
            )
            if (read >= 1) {
                val status = buf[0].toInt() and 0xFF
                val paperEmpty = (status and 0x20) != 0
                val selected = (status and 0x10) != 0
                val noError = (status and 0x08) != 0
                when {
                    paperEmpty -> "Kertas Habis (USB)"
                    !noError -> "Printer Error (USB)"
                    selected -> "Online & Siap (USB OTG)"
                    else -> "Terpasang (USB OTG)"
                }
            } else {
                "Online (USB OTG)"
            }
        } finally {
            conn.close()
        }
    }

    suspend fun sendPrintJob(
        printer: PrinterDevice,
        file: File,
        copies: Int,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val info = findMatchingUsbPrinter(printer)
                ?: throw IllegalStateException("Printer USB OTG tidak terdeteksi. Periksa kabel OTG dan nyalakan printer.")

            if (!usbManager.hasPermission(info.device)) {
                val granted = requestPermission(info.device)
                if (!granted) {
                    throw SecurityException("Izin akses USB ditolak untuk ${info.displayName}.")
                }
            }

            val connection = usbManager.openDevice(info.device)
                ?: throw IllegalStateException("Gagal membuka koneksi USB ke ${info.displayName}.")

            try {
                if (!connection.claimInterface(info.printerInterface, true)) {
                    throw IllegalStateException("Gagal mengklaim antarmuka printer USB.")
                }

                try {
                    val maxPacket = info.outEndpoint.maxPacketSize.coerceIn(64, 16 * 1024)
                    val chunkSize = (maxPacket * 16).coerceIn(4096, 16 * 1024)
                    val totalSize = (file.length() * copies.coerceAtLeast(1)).coerceAtLeast(1L)
                    var sentTotal = 0L

                    repeat(copies.coerceIn(1, 50)) {
                        FileInputStream(file).use { fis ->
                            val buffer = ByteArray(chunkSize)
                            while (true) {
                                val read = fis.read(buffer)
                                if (read <= 0) break

                                var offset = 0
                                while (offset < read) {
                                    val sliceLen = min(read - offset, chunkSize)
                                    val slice = if (offset == 0 && sliceLen == buffer.size) {
                                        buffer
                                    } else {
                                        buffer.copyOfRange(offset, offset + sliceLen)
                                    }
                                    val written = connection.bulkTransfer(info.outEndpoint, slice, sliceLen, 15_000)
                                    if (written < 0) {
                                        throw IllegalStateException("Gagal mengirim paket USB (bulkTransfer=$written)")
                                    }
                                    offset += written
                                    sentTotal += written
                                    onProgress((sentTotal.toFloat() / totalSize.toFloat()).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                } finally {
                    connection.releaseInterface(info.printerInterface)
                }
            } finally {
                connection.close()
            }
        }
    }

    private fun findPrinterInterfaceAndEndpoints(
        device: UsbDevice
    ): Triple<UsbInterface, UsbEndpoint, UsbEndpoint?>? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val isPrinterClass = iface.interfaceClass == UsbConstants.USB_CLASS_PRINTER ||
                device.deviceClass == UsbConstants.USB_CLASS_PRINTER
            if (!isPrinterClass) continue

            var outEp: UsbEndpoint? = null
            var inEp: UsbEndpoint? = null
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_OUT && outEp == null) {
                        outEp = ep
                    } else if (ep.direction == UsbConstants.USB_DIR_IN && inEp == null) {
                        inEp = ep
                    }
                }
            }
            if (outEp != null) {
                return Triple(iface, outEp, inEp)
            }
        }
        return null
    }

    private fun readIeee1284DeviceId(device: UsbDevice, iface: UsbInterface): String {
        val conn = usbManager.openDevice(device) ?: return ""
        return try {
            val buffer = ByteArray(1024)
            // USB Printer Class GET_DEVICE_ID = 0x00
            val len = conn.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_CLASS or 0x01,
                0,
                0,
                iface.id,
                buffer,
                buffer.size,
                1500
            )
            if (len > 2) {
                String(buffer, 2, len - 2, Charsets.UTF_8).trim()
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        } finally {
            conn.close()
        }
    }

    private fun parseIeee1284Id(raw: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        raw.split(';').forEach { part ->
            val idx = part.indexOf(':')
            if (idx > 0) {
                val key = part.substring(0, idx).trim().uppercase()
                val value = part.substring(idx + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    map[key] = value
                }
            }
        }
        return map
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.aistudio.lanprint.USB_PERMISSION"
    }
}
