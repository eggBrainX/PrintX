package com.example

import android.app.Application
import com.example.data.local.LanPrintDatabase
import com.example.data.repository.LanPrintRepository
import com.example.network.CompanionClient
import com.example.network.EsclScannerClient
import com.example.network.ImapClient
import com.example.network.IppClient
import com.example.network.PrinterDiscoveryManager
import com.example.network.RawAndLprClient
import com.example.print.PrintJobDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LanPrintApplication : Application() {

    lateinit var database: LanPrintDatabase
        private set
    lateinit var repository: LanPrintRepository
        private set
    lateinit var ippClient: IppClient
        private set
    lateinit var rawAndLprClient: RawAndLprClient
        private set
    lateinit var companionClient: CompanionClient
        private set
    lateinit var esclScannerClient: EsclScannerClient
        private set
    lateinit var imapClient: ImapClient
        private set
    lateinit var discoveryManager: PrinterDiscoveryManager
        private set
    lateinit var dispatcher: PrintJobDispatcher
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        database = LanPrintDatabase.getInstance(this)
        repository = LanPrintRepository(database)
        ippClient = IppClient()
        rawAndLprClient = RawAndLprClient()
        companionClient = CompanionClient()
        esclScannerClient = EsclScannerClient()
        imapClient = ImapClient()
        discoveryManager = PrinterDiscoveryManager(
            context = this,
            ippClient = ippClient,
            rawAndLprClient = rawAndLprClient,
            companionClient = companionClient,
            esclScannerClient = esclScannerClient
        )
        dispatcher = PrintJobDispatcher(
            context = this,
            repository = repository,
            ippClient = ippClient,
            rawAndLprClient = rawAndLprClient,
            companionClient = companionClient
        )

        appScope.launch {
            repository.ensureDefaultPresets()
        }
    }
}
