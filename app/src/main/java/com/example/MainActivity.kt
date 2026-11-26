package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ui.AppRoutes
import com.example.ui.AppTab
import com.example.ui.LanPrintViewModel
import com.example.ui.screens.CalendarPrintScreen
import com.example.ui.screens.CameraScanScreen
import com.example.ui.screens.ContactsPrintScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.ImapEmailScreen
import com.example.ui.screens.NetworkScannerScreen
import com.example.ui.screens.PrintHubScreen
import com.example.ui.screens.PrintOptionsScreen
import com.example.ui.screens.PrintersScreen
import com.example.ui.screens.TextEmailPrintScreen
import com.example.ui.screens.ThermalPrintScreen
import com.example.ui.screens.WebPrintScreen
import com.example.ui.theme.LanPrintTheme

class MainActivity : ComponentActivity() {

    private val viewModel: LanPrintViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.handleIncomingIntent(intent)

        setContent {
            LanPrintTheme {
                LanPrintAppShell(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleIncomingIntent(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanPrintAppShell(viewModel: LanPrintViewModel) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val savedPrinters by viewModel.savedPrinters.collectAsStateWithLifecycle()
    val discoveredPrinters by viewModel.discoveredPrinters.collectAsStateWithLifecycle()
    val discoveredScanners by viewModel.discoveredScanners.collectAsStateWithLifecycle()
    val manualScanners by viewModel.manualScanners.collectAsStateWithLifecycle()
    val selectedPrinter by viewModel.selectedPrinter.collectAsStateWithLifecycle()
    val networkStatus by viewModel.networkStatus.collectAsStateWithLifecycle()
    val isRefreshingDashboard by viewModel.isRefreshingDashboard.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val scanMessage by viewModel.scanMessage.collectAsStateWithLifecycle()
    val isProbingManual by viewModel.isProbingManual.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val activeJobs by viewModel.activeJobs.collectAsStateWithLifecycle()
    val printHistory by viewModel.printHistory.collectAsStateWithLifecycle()

    val currentDocument by viewModel.currentDocument.collectAsStateWithLifecycle()
    val currentOptions by viewModel.currentOptions.collectAsStateWithLifecycle()
    val previewPageIndex by viewModel.previewPageIndex.collectAsStateWithLifecycle()
    val totalPages by viewModel.totalPages.collectAsStateWithLifecycle()
    val previewBitmap by viewModel.previewBitmap.collectAsStateWithLifecycle()
    val isRenderingPreview by viewModel.isRenderingPreview.collectAsStateWithLifecycle()

    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val calendarEvents by viewModel.calendarEvents.collectAsStateWithLifecycle()
    val isScanningDoc by viewModel.isScanningDoc.collectAsStateWithLifecycle()
    val lastScannedFile by viewModel.lastScannedFile.collectAsStateWithLifecycle()
    val imapEmails by viewModel.imapEmails.collectAsStateWithLifecycle()
    val isFetchingImap by viewModel.isFetchingImap.collectAsStateWithLifecycle()

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.executePrint {
            navController.navigate(AppRoutes.HISTORY) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
            }
        }
    }

    fun triggerPrintWithNotifCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        viewModel.executePrint {
            navController.navigate(AppRoutes.HISTORY) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.pendingNavEvent.collect { route ->
            navController.navigate(route) {
                launchSingleTop = true
            }
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = navBackStackEntry?.destination
    val topLevelRoutes = setOf(AppRoutes.PRINT_HUB, AppRoutes.PRINTERS, AppRoutes.HISTORY)
    val showMainChrome = currentDest?.route in topLevelRoutes

    val navTabs = listOf(AppTab.PRINT_HUB, AppTab.PRINTERS, AppTab.HISTORY)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 680.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                if (showMainChrome) {
                    CenterAlignedTopAppBar(
                        title = {
                            Text(
                                text = "LAN Print",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    )
                }
            },
            bottomBar = {
                if (showMainChrome && !useRail) {
                    NavigationBar {
                        navTabs.forEach { tab ->
                            val selected = currentDest?.hierarchy?.any { it.route == tab.route } == true
                            val icon = when (tab) {
                                AppTab.PRINT_HUB -> if (selected) Icons.Filled.Print else Icons.Outlined.Print
                                AppTab.PRINTERS -> if (selected) Icons.Filled.Router else Icons.Outlined.Router
                                AppTab.HISTORY -> if (selected) Icons.Filled.History else Icons.Outlined.History
                            }
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag("nav_tab_${tab.route}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (showMainChrome && useRail) {
                    NavigationRail {
                        navTabs.forEach { tab ->
                            val selected = currentDest?.hierarchy?.any { it.route == tab.route } == true
                            val icon = when (tab) {
                                AppTab.PRINT_HUB -> if (selected) Icons.Filled.Print else Icons.Outlined.Print
                                AppTab.PRINTERS -> if (selected) Icons.Filled.Router else Icons.Outlined.Router
                                AppTab.HISTORY -> if (selected) Icons.Filled.History else Icons.Outlined.History
                            }
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag("rail_tab_${tab.route}")
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = AppRoutes.PRINT_HUB
                    ) {
                        composable(AppRoutes.PRINT_HUB) {
                            PrintHubScreen(
                                savedPrinters = savedPrinters,
                                selectedPrinter = selectedPrinter,
                                networkStatus = networkStatus,
                                isRefreshingDashboard = isRefreshingDashboard,
                                presets = presets,
                                activeJobs = activeJobs,
                                onRefreshDashboard = { viewModel.refreshDashboardStatus() },
                                onSelectPrinter = { viewModel.selectPrinter(it) },
                                onPickImages = { uris -> viewModel.openImagesForPrint(uris) },
                                onPickDocument = { uri -> viewModel.openDocumentUriForPrint(uri) },
                                onOpenCameraScan = { ktpMode ->
                                    navController.navigate("${AppRoutes.CAMERA_SCAN}?ktp=$ktpMode")
                                },
                                onOpenNetworkScanner = { navController.navigate(AppRoutes.NETWORK_SCANNER) },
                                onOpenThermalPrint = { navController.navigate(AppRoutes.THERMAL_PRINT) },
                                onOpenWebPrint = { navController.navigate(AppRoutes.WEB_PRINT) },
                                onOpenContactsPrint = { navController.navigate(AppRoutes.CONTACTS_PRINT) },
                                onOpenCalendarPrint = { navController.navigate(AppRoutes.CALENDAR_PRINT) },
                                onOpenTextEmailPrint = { navController.navigate(AppRoutes.TEXT_EMAIL_PRINT) },
                                onOpenImapEmail = { navController.navigate(AppRoutes.IMAP_EMAIL) },
                                onPrintTestPage = { viewModel.openTestPageForPrint() },
                                onApplyPreset = { viewModel.applyPreset(it) },
                                onNavigateToPrinters = {
                                    navController.navigate(AppRoutes.PRINTERS) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                    }
                                },
                                onNavigateToHistory = {
                                    navController.navigate(AppRoutes.HISTORY) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                    }
                                }
                            )
                        }

                        composable(AppRoutes.PRINTERS) {
                            PrintersScreen(
                                savedPrinters = savedPrinters,
                                discoveredPrinters = discoveredPrinters,
                                selectedPrinter = selectedPrinter,
                                networkStatus = networkStatus,
                                isScanning = isScanning,
                                scanMessage = scanMessage,
                                isProbingManual = isProbingManual,
                                onStartScan = { viewModel.startDiscovery() },
                                onStopScan = { viewModel.stopDiscovery() },
                                onSelectPrinter = { printer -> viewModel.selectPrinter(printer) },
                                onSetDefaultPrinter = { viewModel.setDefaultPrinter(it) },
                                onDeletePrinter = { viewModel.deleteSavedPrinter(it) },
                                onAddManualPrinter = { name, host, port, path, proto, onPin, onDone ->
                                    viewModel.addAndProbeManualPrinter(name, host, port, path, proto, onPin, onDone)
                                },
                                onPairCompanion = { host, port, https, pin, onPaired ->
                                    viewModel.pairWithCompanion(host, port, https, pin, onPaired)
                                }
                            )
                        }

                        composable(AppRoutes.HISTORY) {
                            HistoryScreen(
                                activeJobs = activeJobs,
                                history = printHistory,
                                onCancelJob = { viewModel.cancelActiveJob(it) },
                                onDismissCompleted = { viewModel.dismissCompletedJobs() },
                                onDeleteHistory = { viewModel.deleteHistoryItem(it) },
                                onClearHistory = { viewModel.clearHistory() }
                            )
                        }

                        composable(AppRoutes.PRINT_OPTIONS) {
                            PrintOptionsScreen(
                                document = currentDocument,
                                selectedPrinter = selectedPrinter,
                                options = currentOptions,
                                presets = presets,
                                previewBitmap = previewBitmap,
                                previewPageIndex = previewPageIndex,
                                totalPages = totalPages,
                                isRenderingPreview = isRenderingPreview,
                                onBack = { navController.popBackStack() },
                                onUpdateOptions = { viewModel.updateOptions(it) },
                                onSelectPage = { viewModel.setPreviewPage(it) },
                                onApplyPreset = { viewModel.applyPreset(it) },
                                onSavePreset = { viewModel.saveCurrentAsPreset(it) },
                                onChoosePrinter = { navController.navigate(AppRoutes.PRINTERS) },
                                onPrintNow = { triggerPrintWithNotifCheck() }
                            )
                        }

                        composable(
                            route = "${AppRoutes.CAMERA_SCAN}?ktp={ktp}",
                            arguments = listOf(navArgument("ktp") {
                                type = NavType.BoolType
                                defaultValue = false
                            })
                        ) { backStackEntry ->
                            val ktpMode = backStackEntry.arguments?.getBoolean("ktp") ?: false
                            CameraScanScreen(
                                initialKtpMode = ktpMode,
                                onBack = { navController.popBackStack() },
                                onContinueToPrint = { uris, preset, cleanDoc ->
                                    viewModel.openImagesForPrint(uris, preset, cleanDoc)
                                }
                            )
                        }

                        composable(AppRoutes.NETWORK_SCANNER) {
                            NetworkScannerScreen(
                                discoveredScanners = discoveredScanners,
                                manualScanners = manualScanners,
                                isScanningDoc = isScanningDoc,
                                lastScannedFile = lastScannedFile,
                                onDiscover = { viewModel.startDiscovery(includeSubnetScan = false) },
                                onProbeManualScanner = { h, p, https -> viewModel.probeManualEsclScanner(h, p, https) },
                                onRunScan = { sc, dpi, cm, fmt -> viewModel.runNetworkScan(sc, dpi, cm, fmt) },
                                onOpenScannedForPrint = { f -> viewModel.openScannedFileForPrint(f) },
                                onBack = { navController.popBackStack() }
                            )
                        }

                        composable(AppRoutes.THERMAL_PRINT) {
                            ThermalPrintScreen(
                                defaultHost = selectedPrinter?.host.orEmpty(),
                                onPrintThermal = { h, p, bytes, title ->
                                    viewModel.printThermalEscPos(h, p, bytes, title)
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }

                        composable(AppRoutes.IMAP_EMAIL) {
                            ImapEmailScreen(
                                emails = imapEmails,
                                isFetching = isFetchingImap,
                                onFetch = { h, p, em, pw -> viewModel.fetchImapEmails(h, p, em, pw) },
                                onPrintEmail = { subj, body ->
                                    viewModel.openTextOrEmailForPrint(subj, body, isHtml = false)
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }

                        composable(
                            route = "${AppRoutes.WEB_PRINT}?url={url}",
                            arguments = listOf(navArgument("url") {
                                type = NavType.StringType
                                defaultValue = "https://www.wikipedia.org"
                            })
                        ) { backStackEntry ->
                            val rawUrl = backStackEntry.arguments?.getString("url") ?: "https://www.wikipedia.org"
                            WebPrintScreen(
                                initialUrl = Uri.decode(rawUrl),
                                onBack = { navController.popBackStack() },
                                onCaptureWebPage = { title, snapshots ->
                                    viewModel.openWebBitmapsForPrint(title, snapshots)
                                }
                            )
                        }

                        composable(AppRoutes.CONTACTS_PRINT) {
                            ContactsPrintScreen(
                                contacts = contacts,
                                onLoadContacts = { viewModel.loadContacts() },
                                onPrintContacts = { viewModel.openContactsForPrint(it) },
                                onBack = { navController.popBackStack() }
                            )
                        }

                        composable(AppRoutes.CALENDAR_PRINT) {
                            CalendarPrintScreen(
                                events = calendarEvents,
                                onLoadEvents = { viewModel.loadCalendarEvents(it) },
                                onPrintEvents = { events, range -> viewModel.openCalendarForPrint(events, range) },
                                onBack = { navController.popBackStack() }
                            )
                        }

                        composable(AppRoutes.TEXT_EMAIL_PRINT) {
                            TextEmailPrintScreen(
                                onPrintText = { title, body, isHtml ->
                                    viewModel.openTextOrEmailForPrint(title, body, isHtml)
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
