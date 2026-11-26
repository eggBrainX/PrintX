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
    private val companionClient: CompanionClient
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _discoveredPrinters = MutableStateFlow<List<PrinterDevice>>(emptyList())
    val discoveredPrinters: StateFlow<List<PrinterDevice>> = _discoveredPrinters.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanMessage = MutableStateFlow("")
    val scanMessage: StateFlow<String> = _scanMessage.asStateFlow()

    private val foundMap = ConcurrentHashMap<String, PrinterDevice>()
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

    fun startDiscovery(scope: CoroutineScope, includeSubnetScan: Boolean = true) {
        stopDiscovery()
        _isScanning.value = true
        _scanMessage.value = "Mencari printer mDNS (_ipp, _ipps, _pdl-datastream, _lanprint)..."
        foundMap.clear()
        _discoveredPrinters.value = emptyList()

        runCatching {
            multicastLock = wifiManager.createMulticastLock("lanprint_mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        val serviceTypes = listOf(
            "_ipp._tcp." to PrinterProtocol.IPP,
            "_ipps._tcp." to PrinterProtocol.IPPS,
            "_printer._tcp." to PrinterProtocol.LPR_515,
            "_pdl-datastream._tcp." to PrinterProtocol.RAW_9100,
            "_lanprint._tcp." to PrinterProtocol.COMPANION_HTTP
        )

        serviceTypes.forEach { (serviceType, protocol) ->
            registerMdnsListener(scope, serviceType, protocol)
        }

        scanJob = scope.launch(Dispatchers.IO) {
            if (includeSubnetScan) {
                val status = getNetworkStatus()
                if (status.subnetPrefix != null) {
                    _scanMessage.value = "Memindai mDNS & subnet ${status.subnetPrefix}.1-254 (port 631, 9100, 8765)..."
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
        if (_discoveredPrinters.value.isEmpty()) {
            _scanMessage.value = "Selesai memindai. Tambahkan IP manual jika router memblokir mDNS."
        } else {
            _scanMessage.value = "Ditemukan ${_discoveredPrinters.value.size} perangkat cetak."
        }
    }

    private fun registerMdnsListener(
        scope: CoroutineScope,
        serviceType: String,
        protocol: PrinterProtocol
    ) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolveServiceCompat(scope, serviceInfo, protocol)
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
        protocol: PrinterProtocol
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
                            val rpBytes = info.attributes["rp"]
                            val rp = rpBytes?.let { String(it) }?.let { if (it.startsWith("/")) it else "/$it" }
                                ?: "/ipp/print"
                            scope.launch(Dispatchers.IO) {
                                handleResolvedHost(info.serviceName, host, info.port, rp, protocol)
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
                        val rpBytes = resolved.attributes["rp"]
                        val rp = rpBytes?.let { String(it) }?.let { if (it.startsWith("/")) it else "/$it" }
                            ?: "/ipp/print"
                        scope.launch(Dispatchers.IO) {
                            handleResolvedHost(resolved.serviceName, host, resolved.port, rp, protocol)
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
