package com.example.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class EsclScannerInfo(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val useHttps: Boolean,
    val resourcePath: String = "/eSCL",
    val resolutions: List<Int> = listOf(150, 300, 600),
    val formats: List<String> = listOf("application/pdf", "image/jpeg")
) {
    val baseUrl: String
        get() = "${if (useHttps) "https" else "http"}://$host:$port${resourcePath.trimEnd('/')}"
}

class EsclScannerClient {

    private val client: OkHttpClient by lazy {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAll, SecureRandom())
        }
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    suspend fun getCapabilities(
        host: String,
        port: Int = 80,
        useHttps: Boolean = false,
        resourcePath: String = "/eSCL"
    ): Result<EsclScannerInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val scheme = if (useHttps) "https" else "http"
            val cleanRs = if (resourcePath.startsWith("/")) resourcePath.trimEnd('/') else "/${resourcePath.trimEnd('/')}"
            val url = "$scheme://$host:$port$cleanRs/ScannerCapabilities"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                val xml = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("eSCL HTTP ${resp.code}: ${resp.message}")
                }
                parseCapabilitiesXml(xml, host, port, useHttps, cleanRs)
            }
        }
    }

    suspend fun scanDocument(
        context: Context,
        scanner: EsclScannerInfo,
        dpi: Int = 300,
        colorMode: String = "RGB24", // RGB24 or Grayscale8
        format: String = "application/pdf" // application/pdf or image/jpeg
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val scanSettingsXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <scan:ScanSettings xmlns:pwg="http://www.pwg.org/schemas/2010/12/sm"
                                   xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03">
                    <pwg:Version>2.0</pwg:Version>
                    <pwg:ScanRegions>
                        <pwg:ScanRegion>
                            <pwg:ContentRegionUnits>escl:ThreeHundredthsOfInches</pwg:ContentRegionUnits>
                            <pwg:XOffset>0</pwg:XOffset>
                            <pwg:YOffset>0</pwg:YOffset>
                            <pwg:Width>2480</pwg:Width>
                            <pwg:Height>3508</pwg:Height>
                        </pwg:ScanRegion>
                    </pwg:ScanRegions>
                    <pwg:InputSource>Platen</pwg:InputSource>
                    <scan:ColorMode>$colorMode</scan:ColorMode>
                    <scan:XResolution>$dpi</scan:XResolution>
                    <scan:YResolution>$dpi</scan:YResolution>
                    <pwg:DocumentFormat>$format</pwg:DocumentFormat>
                </scan:ScanSettings>
            """.trimIndent()

            val createReq = Request.Builder()
                .url("${scanner.baseUrl}/ScanJobs")
                .post(scanSettingsXml.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                .build()

            val locationUrl = client.newCall(createReq).execute().use { resp ->
                if (resp.code !in 200..201) {
                    throw IllegalStateException("Gagal memulai scan (HTTP ${resp.code})")
                }
                resp.header("Location") ?: throw IllegalStateException("Scanner tidak mengembalikan URL job")
            }

            val nextDocUrl = if (locationUrl.startsWith("http")) {
                "${locationUrl.trimEnd('/')}/NextDocument"
            } else {
                val scheme = if (scanner.useHttps) "https" else "http"
                "$scheme://${scanner.host}:${scanner.port}${locationUrl.trimEnd('/')}/NextDocument"
            }

            // Wait briefly for flatbed pass
            delay(1500)
            val ext = if (format == "application/pdf") "pdf" else "jpg"
            val outFile = File(context.cacheDir, "escl_scan_${System.currentTimeMillis()}.$ext")

            var attempt = 0
            var downloaded = false
            while (attempt < 15 && !downloaded) {
                attempt++
                val docReq = Request.Builder().url(nextDocUrl).get().build()
                client.newCall(docReq).execute().use { resp ->
                    when (resp.code) {
                        200 -> {
                            resp.body?.byteStream()?.use { inp ->
                                FileOutputStream(outFile).use { out -> inp.copyTo(out) }
                            }
                            downloaded = true
                        }
                        503 -> delay(1200) // Scanner still warming/scanning
                        else -> throw IllegalStateException("Gagal mengunduh hasil scan (HTTP ${resp.code})")
                    }
                }
            }
            if (!downloaded) throw IllegalStateException("Waktu tunggu scan habis")
            outFile
        }
    }

    private fun parseCapabilitiesXml(
        xml: String,
        host: String,
        port: Int,
        useHttps: Boolean,
        resourcePath: String
    ): EsclScannerInfo {
        var makeAndModel = "Scanner eSCL ($host)"
        val resolutions = mutableSetOf<Int>()
        val formats = mutableSetOf<String>()

        runCatching {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
            val parser = factory.newPullParser().apply { setInput(StringReader(xml)) }
            var currentTag = ""
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> currentTag = parser.name.orEmpty()
                    XmlPullParser.TEXT -> {
                        val txt = parser.text?.trim().orEmpty()
                        if (txt.isNotEmpty()) {
                            when (currentTag) {
                                "MakeAndModel" -> makeAndModel = txt
                                "XResolution" -> txt.toIntOrNull()?.let { resolutions.add(it) }
                                "DocumentFormat" -> formats.add(txt)
                            }
                        }
                    }
                }
                parser.next()
            }
        }

        return EsclScannerInfo(
            id = "escl_${host}_$port",
            name = makeAndModel,
            host = host,
            port = port,
            useHttps = useHttps,
            resourcePath = resourcePath,
            resolutions = resolutions.sorted().ifEmpty { listOf(150, 300, 600) },
            formats = formats.toList().ifEmpty { listOf("application/pdf", "image/jpeg") }
        )
    }
}
