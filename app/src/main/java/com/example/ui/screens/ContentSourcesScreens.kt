package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.rendering.CalendarEventEntry
import com.example.rendering.ContactEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebPrintScreen(
    initialUrl: String = "https://www.wikipedia.org",
    onBack: () -> Unit,
    onCaptureWebPage: (title: String, snapshots: List<Bitmap>) -> Unit
) {
    var urlInput by remember { mutableStateOf(initialUrl) }
    var pageTitle by remember { mutableStateOf("Halaman Web") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun loadUrl(raw: String) {
        val trimmed = raw.trim()
        val normalized = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        urlInput = normalized
        webViewRef?.loadUrl(normalized)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cetak Halaman Web", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                actions = {
                    IconButton(onClick = { webViewRef?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Muat Ulang")
                    }
                    Button(
                        onClick = {
                            val wv = webViewRef ?: return@Button
                            val w = wv.width.coerceAtLeast(800)
                            val totalH = (wv.contentHeight * wv.scale).toInt().coerceAtLeast(wv.height.coerceAtLeast(1000))
                            val sliceH = (w * 1.414f).toInt().coerceAtLeast(600)
                            val fullBmp = Bitmap.createBitmap(w, totalH.coerceAtMost(sliceH * 10), Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(fullBmp)
                            wv.draw(canvas)

                            val slices = mutableListOf<Bitmap>()
                            var y = 0
                            while (y < fullBmp.height && slices.size < 10) {
                                val h = minOf(sliceH, fullBmp.height - y)
                                slices.add(Bitmap.createBitmap(fullBmp, 0, y, w, h))
                                y += h
                            }
                            onCaptureWebPage(pageTitle, slices)
                        },
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .testTag("btn_capture_webpage")
                    ) {
                        Icon(Icons.Default.Print, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Cetak")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    singleLine = true,
                    label = { Text("URL Situs Web") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { loadUrl(urlInput) }),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { loadUrl(urlInput) },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text("Buka")
                }
            }

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                pageTitle = view?.title ?: "Halaman Web"
                                if (!url.isNullOrBlank()) urlInput = url
                            }
                        }
                        loadUrl(urlInput)
                        webViewRef = this
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsPrintScreen(
    contacts: List<ContactEntry>,
    onLoadContacts: () -> Unit,
    onPrintContacts: (List<ContactEntry>) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var search by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (granted) onLoadContacts()
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) onLoadContacts()
    }

    val filtered = remember(contacts, search) {
        if (search.isBlank()) contacts
        else contacts.filter {
            it.name.contains(search, ignoreCase = true) ||
                it.phones.any { p -> p.contains(search) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cetak Kontak") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                actions = {
                    if (hasPermission && filtered.isNotEmpty()) {
                        TextButton(onClick = {
                            selectedIds = if (selectedIds.size == filtered.size) emptySet() else filtered.map { it.id }.toSet()
                        }) {
                            Text(if (selectedIds.size == filtered.size) "Batal Semua" else "Pilih Semua")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (hasPermission) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Button(
                        enabled = selectedIds.isNotEmpty(),
                        onClick = {
                            val chosen = contacts.filter { it.id in selectedIds }
                            onPrintContacts(chosen)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("btn_print_selected_contacts")
                    ) {
                        Icon(Icons.Default.Print, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Pratinjau & Cetak (${selectedIds.size} Kontak)")
                    }
                }
            }
        }
    ) { innerPadding ->
        if (!hasPermission) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Izin Akses Kontak Diperlukan",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "LAN Print hanya membaca kontak saat Anda memilih daftar kontak untuk dicetak.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { launcher.launch(Manifest.permission.READ_CONTACTS) }) {
                    Text("Izinkan Akses Kontak")
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("Cari nama atau nomor...") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { contact ->
                        val checked = contact.id in selectedIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedIds = if (checked) selectedIds - contact.id else selectedIds + contact.id
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    selectedIds = if (checked) selectedIds - contact.id else selectedIds + contact.id
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(contact.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = (contact.phones + contact.emails).joinToString(" • ").ifBlank { "Tidak ada nomor/email" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarPrintScreen(
    events: List<CalendarEventEntry>,
    onLoadEvents: (daysAhead: Int) -> Unit,
    onPrintEvents: (List<CalendarEventEntry>, String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var daysAhead by remember { mutableIntStateOf(7) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val df = remember { SimpleDateFormat("EEE, dd MMM HH:mm", Locale("id", "ID")) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (granted) onLoadEvents(daysAhead)
    }

    LaunchedEffect(hasPermission, daysAhead) {
        if (hasPermission) onLoadEvents(daysAhead)
    }

    LaunchedEffect(events) {
        selectedIds = events.map { it.id }.toSet()
    }

    val rangeLabel = when (daysAhead) {
        1 -> "Hari Ini"
        7 -> "7 Hari Ke Depan"
        else -> "30 Hari Ke Depan"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cetak Agenda Kalender") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        },
        bottomBar = {
            if (hasPermission) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Button(
                        enabled = selectedIds.isNotEmpty(),
                        onClick = {
                            val chosen = events.filter { it.id in selectedIds }
                            onPrintEvents(chosen, rangeLabel)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("btn_print_selected_events")
                    ) {
                        Icon(Icons.Default.Print, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Pratinjau & Cetak (${selectedIds.size} Agenda)")
                    }
                }
            }
        }
    ) { innerPadding ->
        if (!hasPermission) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Izin Akses Kalender Diperlukan", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Diperlukan untuk membaca jadwal acara agar bisa dicetak sebagai agenda PDF.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { launcher.launch(Manifest.permission.READ_CALENDAR) }) {
                    Text("Izinkan Akses Kalender")
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(1 to "Hari Ini", 7 to "7 Hari", 30 to "30 Hari").forEach { (d, lbl) ->
                        FilterChip(
                            selected = daysAhead == d,
                            onClick = { daysAhead = d },
                            label = { Text(lbl) }
                        )
                    }
                }

                if (events.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Tidak ada agenda dalam rentang $rangeLabel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(events, key = { it.id }) { ev ->
                            val checked = ev.id in selectedIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedIds = if (checked) selectedIds - ev.id else selectedIds + ev.id
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        selectedIds = if (checked) selectedIds - ev.id else selectedIds + ev.id
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(ev.title.ifBlank { "(Tanpa Judul)" }, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "${df.format(Date(ev.startTimeMillis))}${if (ev.location.isNotBlank()) " • ${ev.location}" else ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEmailPrintScreen(
    onPrintText: (title: String, body: String, isHtml: Boolean) -> Unit,
    onBack: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var isHtml by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cetak Teks / Email") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        },
        bottomBar = {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Button(
                    enabled = body.isNotBlank(),
                    onClick = { onPrintText(title, body, isHtml) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("btn_print_text_submit")
                ) {
                    Icon(Icons.Default.Print, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Pratinjau & Cetak")
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Tempel isi email, pesan, atau catatan untuk dicetak. Anda juga dapat langsung Share pesan/email dari aplikasi lain ke LAN Print.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Judul / Subjek (opsional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Format HTML (Email)", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = isHtml, onCheckedChange = { isHtml = it })
            }
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("Isi Teks / Email") },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}
