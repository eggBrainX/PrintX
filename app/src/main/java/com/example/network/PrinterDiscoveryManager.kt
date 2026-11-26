package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.example.domain.model.PrinterCapabilities
import com.example.domain.model.PrinterDevice
import com.example.domain.model.PrinterProtocol
import com.example.usb.UsbPrinterManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

data class NetworkStatus(
    val isWifiConnected: Boolean = false,
    val localIp: String? = null,
    val subnetPrefix: String? = null
)

class PrinterDiscoveryManager(
    private val context: Context,
    private val ippClient: IppClient,
    private val rawAndLprClient: RawAndLprClient,
    private val companionClient: CompanionClient,
    private val esclScannerClient: EsclScannerClient,
    private val smbPrinterClient: SmbPrinterClient,
    private val usbPrinterManager: UsbPrinterManager
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _discoveredPrinters = MutableStateFlow<List<PrinterDevice>>(emptyList())
    val discoveredPrinters: StateFlow<List<PrinterDevice>> = _discoveredPrinters.asStateFlow()

    private val _discoveredScanners = MutableStateFlow<List<EsclScannerInfo>>(emptyList())
    val discoveredScanners: StateFlow<List<EsclScannerInfo>> = _discoveredScanners.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanMessage = MutableStateFlow("")
    val scanMessage: StateFlow<String> = _scanMessage.asStateFlow()

    private val foundMap = ConcurrentHashMap<String, PrinterDevice>()
    private val foundScannersMap = ConcurrentHashMap<String, EsclScannerInfo>()
    private val activeListeners = mutableListOf<NsdManager.DiscoveryListener>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var scanJob: Job? = null

    fun getNetworkStatus(): NetworkStatus {
        val activeNet = connectivityManager.activeNetwork
        val linkProps: LinkProperties? = activeNet?.let { connectivityManager.getLinkProperties(it) }
        val ipv4 = linkProps?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress

        val prefix = ipv4?.substringBeforeLast('.')
        return NetworkStatus(
            isWifiConnected = ipv4 != null,
            localIp = ipv4,
            subnetPrefix = prefix
        )
    }

    suspend fun probePrinterStatus(printer: PrinterDevice): PrinterDevice {
        return when (printer.protocol) {
            PrinterProtocol.IPP, PrinterProtocol.IPPS -> {
                val res = ippClient.getPrinterAttributes(printer.ippHttpUrl)
                res.fold(
                    onSuccess = { caps ->
                        printer.copy(isOnline = true, capabilities = caps)
                    },
                    onFailure = {
                        val tcpOk = rawAndLprClient.probePort(printer.host, printer.port, 1200)
                        printer.copy(
                            isOnline = tcpOk,
                            capabilities = printer.capabilities.copy(
                                printerStateMessage = if (tcpOk) "Online (IPP tidak merespons)" else "Offline / Tidak Terjangkau",
                                lastProbedAt = System.currentTimeMillis()
                            )
                        )
                    }
                )
            }
            PrinterProtocol.COMPANION_HTTP, PrinterProtocol.COMPANION_HTTPS -> {
                val useHttps = printer.protocol == PrinterProtocol.COMPANION_HTTPS
                val res = companionClient.fetchPrinters(printer.host, printer.port, useHttps, printer.pairingToken)
                res.fold(
                    onSuccess = { list ->
                        val match = list.firstOrNull { it.companionPrinterName == printer.companionPrinterName }
                        printer.copy(
                            isOnline = match != null,
                            capabilities = (match?.capabilities ?: printer.capabilities).copy(
                                printerStateMessage = if (match != null) "Online via Laptop" else "Printer USB Dicabut",
                                lastProbedAt = System.currentTimeMillis()
                            )
                        )
                    },
                    onFailure = {
                        printer.copy(
                            isOnline = false,
                            capabilities = printer.capabilities.copy(
                                printerStateMessage = "Companion Laptop Offline",
                                lastProbedAt = System.currentTimeMillis()
                            )
                        )
                    }
                )
            }
            PrinterProtocol.RAW_9100, PrinterProtocol.LPR_515 -> {
                val online = rawAndLprClient.probePort(printer.host, printer.port, 1500)
                printer.copy(
                    isOnline = online,
                    capabilities = printer.capabilities.copy(
                        printerStateMessage = if (online) "Online (Port ${printer.port})" else "Offline / Tidak Terjangkau",
                        lastProbedAt = System.currentTimeMillis()
                    )
                )
            }
            PrinterProtocol.SMB_WINDOWS -> {
                val online = rawAndLprClient.probePort(printer.host, 445, 1500) &&
                    smbPrinterClient.checkShareOnline(printer)
                printer.copy(
                    isOnline = online,
                    capabilities = printer.capabilities.copy(
                        printerStateMessage = if (online) "Share Windows Online" else "Share Windows Offline",
                        lastProbedAt = System.currentTimeMillis()
                    )
                )
            }
            PrinterProtocol.USB_OTG -> {
                val usbInfo = usbPrinterManager.findMatchingUsbPrinter(printer)
                val online = usbInfo != null
                val stateMsg = if (usbInfo != null) {
                    usbPrinterManager.readPortStatusLabel(usbInfo)
                } else {
                    "Kabel USB OTG Terputus"
                }
                printer.copy(
                    isOnline = online,
                    capabilities = printer.capabilities.copy(
                        printerStateMessage = stateMsg,
                        lastProbedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    fun startDiscovery(scope: CoroutineScope, includeSubnetScan: Boolean = true) {
        stopDiscovery()
        _isScanning.value = true
        _scanMessage.value = "Mencari printer mDNS, Windows SMB Share, & USB OTG..."
        foundMap.clear()
        foundScannersMap.clear()
        _discoveredPrinters.value = emptyList()
        _discoveredScanners.value = emptyList()

        // Also detect connected USB OTG printers immediately
        usbPrinterManager.refreshConnectedPrinters().forEach { usbInfo ->
            addDiscovered(usbInfo.toPrinterDevice())
        }

        runCatching {
            multicastLock = wifiManager.createMulticastLock("lanprint_mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        val printerServices = listOf(
            "_ipp._tcp." to PrinterProtocol.IPP,
            "_ipps._tcp." to PrinterProtocol.IPPS,
            "_printer._tcp." to PrinterProtocol.LPR_515,
            "_pdl-datastream._tcp." to PrinterProtocol.RAW_9100,
            "_smb._tcp." to PrinterProtocol.SMB_WINDOWS,
            "_lanprint._tcp." to PrinterProtocol.COMPANION_HTTP
        )

        printerServices.forEach { (serviceType, protocol) ->
            registerMdnsPrinterListener(scope, serviceType, protocol)
        }
        registerMdnsScannerListener(scope, "_uscan._tcp.", useHttps = false)
        registerMdnsScannerListener(scope, "_uscans._tcp.", useHttps = true)

        scanJob = scope.launch(Dispatchers.IO) {
            if (includeSubnetScan) {
                val status = getNetworkStatus()
                if (status.subnetPrefix != null) {
                    _scanMessage.value = "Memindai mDNS & subnet ${status.subnetPrefix}.1-254 (IPP 631, RAW 9100, LPR 515, SMB 445, Companion 8765)..."
                    scanSubnetPorts(status.subnetPrefix, status.localIp)
                }
            }
            delay(10_000)
            stopDiscovery()
        }
    }

    fun stopDiscovery() {
        scanJob?.cancel()
        scanJob = null
        activeListeners.forEach { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        activeListeners.clear()
        runCatching {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        }
        multicastLock = null
        _isScanning.value = false
        _scanMessage.value = if (_discoveredPrinters.value.isEmpty() && _discoveredScanners.value.isEmpty()) {
            "Selesai memindai. Tambahkan IP manual jika router memblokir mDNS."
        } else {
            "Ditemukan ${_discoveredPrinters.value.size} printer & ${_discoveredScanners.value.size} scanner."
        }
    }

    private fun registerMdnsPrinterListener(
        scope: CoroutineScope,
        serviceType: String,
        protocol: PrinterProtocol
    ) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolveServiceCompat(scope, serviceInfo) { name, host, port, txt ->
                    val rp = txt["rp"]?.let { if (it.startsWith("/")) it else "/$it" } ?: "/ipp/print"
                    handleResolvedHost(name, host, port, rp, protocol)
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        activeListeners.add(listener)
        runCatching {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    private fun registerMdnsScannerListener(
        scope: CoroutineScope,
        serviceType: String,
        useHttps: Boolean
    ) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolveServiceCompat(scope, serviceInfo) { _, host, port, txt ->
                    val rs = txt["rs"]?.let { if (it.startsWith("/")) it else "/$it" } ?: "/eSCL"
                    esclScannerClient.getCapabilities(host, port, useHttps, rs).onSuccess { info ->
                        foundScannersMap[info.id] = info
                        _discoveredScanners.value = foundScannersMap.values.sortedBy { it.name }
                    }
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        activeListeners.add(listener)
        runCatching {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveServiceCompat(
        scope: CoroutineScope,
        serviceInfo: NsdServiceInfo,
        onResolved: suspend (name: String, host: String, port: Int, txt: Map<String, String>) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                nsdManager.registerServiceInfoCallback(
                    serviceInfo,
                    { it.run() },
                    object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                        override fun onServiceUpdated(info: NsdServiceInfo) {
                            val host = info.hostAddresses.firstOrNull { it is Inet4Address }?.hostAddress
                                ?: info.hostAddresses.firstOrNull()?.hostAddress ?: return
                            val txt = info.attributes.mapValues { (_, v) -> v?.let { String(it) }.orEmpty() }
                            scope.launch(Dispatchers.IO) {
                                onResolved(info.serviceName, host, info.port, txt)
                            }
                            runCatching { nsdManager.unregisterServiceInfoCallback(this) }
                        }
                        override fun onServiceLost() {}
                        override fun onServiceInfoCallbackUnregistered() {}
                    }
                )
            }
        } else {
            runCatching {
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val hostObj: InetAddress = resolved.host ?: return
                        val host = hostObj.hostAddress ?: return
                        val txt = resolved.attributes.mapValues { (_, v) -> v?.let { String(it) }.orEmpty() }
                        scope.launch(Dispatchers.IO) {
                            onResolved(resolved.serviceName, host, resolved.port, txt)
                        }
                    }
                })
            }
        }
    }

    private suspend fun scanSubnetPorts(prefix: String, localIp: String?) = coroutineScope {
        val semaphore = Semaphore(32)
        val targets = (1..254).map { "$prefix.$it" }.filter { it != localIp }
        targets.map { ip ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    if (rawAndLprClient.probePort(ip, 631, 450)) {
                        handleResolvedHost("Printer IPP ($ip)", ip, 631, "/ipp/print", PrinterProtocol.IPP)
                    } else if (rawAndLprClient.probePort(ip, 9100, 400)) {
                        handleResolvedHost("Printer RAW ($ip)", ip, 9100, "", PrinterProtocol.RAW_9100)
                    } else if (rawAndLprClient.probePort(ip, 515, 350)) {
                        handleResolvedHost("Printer LPR ($ip)", ip, 515, "lp", PrinterProtocol.LPR_515)
                    }
                    if (rawAndLprClient.probePort(ip, 445, 420)) {
                        handleResolvedHost("Windows SMB ($ip)", ip, 445, "", PrinterProtocol.SMB_WINDOWS)
                    }
                    if (rawAndLprClient.probePort(ip, 8765, 400)) {
                        handleResolvedHost("Laptop Companion ($ip)", ip, 8765, "/api/v1/jobs", PrinterProtocol.COMPANION_HTTP)
                    }
                }
            }
        }.awaitAll()
    }

    private suspend fun handleResolvedHost(
        serviceName: String,
        host: String,
        port: Int,
        path: String,
        protocol: PrinterProtocol
    ) {
        if (protocol == PrinterProtocol.SMB_WINDOWS) {
            val smbRes = smbPrinterClient.discoverPrintersOnHost(host)
            smbRes.onSuccess { sharedPrinters ->
                sharedPrinters.forEach { addDiscovered(it) }
            }.onFailure { err ->
                if (err is SmbAuthRequiredException) {
                    addDiscovered(
                        PrinterDevice(
                            id = "smb_auth_$host",
                            name = "$serviceName (Butuh Login Windows)",
                            host = host,
                            port = 445,
                            path = "",
                            protocol = PrinterProtocol.SMB_WINDOWS,
                            capabilities = PrinterCapabilities(
                                makeAndModel = "Windows Printer Sharing @ $host",
                                printerStateMessage = "Ketuk untuk masukkan Username/Password Windows"
                            )
                        )
                    )
                }
            }
            return
        }

        if (protocol == PrinterProtocol.COMPANION_HTTP || protocol == PrinterProtocol.COMPANION_HTTPS) {
            val result = companionClient.fetchPrinters(
                host = host,
                port = port,
                useHttps = protocol == PrinterProtocol.COMPANION_HTTPS
            )
            result.onSuccess { printers ->
                printers.forEach { addDiscovered(it) }
            }.onFailure { err ->
                if (err is SecurityException) {
                    addDiscovered(
                        PrinterDevice(
                            id = "companion_pair_${host}_$port",
                            name = "$serviceName (Perlu PIN)",
                            host = host,
                            port = port,
                            path = "/api/v1/jobs",
                            protocol = protocol,
                            capabilities = PrinterCapabilities(
                                makeAndModel = "Companion Server @ $host:$port",
                                printerStateMessage = "Butuh Pairing PIN"
                            )
                        )
                    )
                }
            }
            return
        }

        val id = "${protocol.name}_${host}_$port"
        if (foundMap.containsKey(id)) return

        var caps = PrinterCapabilities(makeAndModel = protocol.label, printerStateMessage = "Online")
        var displayName = serviceName
        if (protocol == PrinterProtocol.IPP || protocol == PrinterProtocol.IPPS) {
            val scheme = if (protocol == PrinterProtocol.IPPS) "https" else "http"
            val url = "$scheme://$host:$port$path"
            ippClient.getPrinterAttributes(url).onSuccess { probed ->
                caps = probed
                if (probed.makeAndModel.isNotBlank()) {
                    displayName = probed.makeAndModel
                }
            }
        }
        addDiscovered(
            PrinterDevice(
                id = id,
                name = displayName,
                host = host,
                port = port,
                path = path,
                protocol = protocol,
                capabilities = caps
            )
        )
    }

    private fun addDiscovered(device: PrinterDevice) {
        foundMap[device.id] = device
        _discoveredPrinters.value = foundMap.values.sortedBy { it.name }
    }
}
