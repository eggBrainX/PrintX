package com.example.network

import android.os.Build
import com.example.data.local.JsonConverters
import com.example.domain.model.PrintOptions
import com.example.domain.model.PrinterCapabilities
import com.example.domain.model.PrinterDevice
import com.example.domain.model.PrinterProtocol
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@JsonClass(generateAdapter = true)
data class CompanionPairRequest(
    val pin: String,
    val deviceName: String
)

@JsonClass(generateAdapter = true)
data class CompanionPairResponse(
    val token: String,
    val serverName: String? = null
)

@JsonClass(generateAdapter = true)
data class CompanionPrinterDto(
    val name: String,
    val displayName: String? = null,
    val supportsColor: Boolean = true,
    val supportsDuplex: Boolean = false,
    val paperSizes: List<String> = listOf("A4", "F4", "LETTER"),
    val trays: List<String> = listOf("auto", "tray-1"),
    val isDefault: Boolean = false
)

@JsonClass(generateAdapter = true)
data class CompanionPrintersResponse(
    val printers: List<CompanionPrinterDto> = emptyList(),
    val requiresPairing: Boolean = false
)

@JsonClass(generateAdapter = true)
data class CompanionJobResponse(
    val jobId: String,
    val status: String,
    val message: String? = null
)

class CompanionClient {
    private val moshi = JsonConverters.moshi
    private val pairReqAdapter = moshi.adapter(CompanionPairRequest::class.java)
    private val pairResAdapter = moshi.adapter(CompanionPairResponse::class.java)
    private val printersResAdapter = moshi.adapter(CompanionPrintersResponse::class.java)
    private val jobResAdapter = moshi.adapter(CompanionJobResponse::class.java)

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
            .writeTimeout(120, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    suspend fun pair(baseUrl: String, pin: String): Result<CompanionPairResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = pairReqAdapter.toJson(
                CompanionPairRequest(pin = pin.trim(), deviceName = "${Build.MANUFACTURER} ${Build.MODEL}")
            )
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/v1/pair")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Pairing gagal (HTTP ${resp.code}): $body")
                }
                pairResAdapter.fromJson(body) ?: throw IllegalStateException("Respons pairing tidak valid")
            }
        }
    }

    suspend fun fetchPrinters(
        host: String,
        port: Int,
        useHttps: Boolean = false,
        token: String? = null
    ): Result<List<PrinterDevice>> = withContext(Dispatchers.IO) {
        runCatching {
            val scheme = if (useHttps) "https" else "http"
            val baseUrl = "$scheme://$host:$port"
            val builder = Request.Builder()
                .url("$baseUrl/api/v1/printers")
                .get()
            if (!token.isNullOrBlank()) {
                builder.header("Authorization", "Bearer $token")
            }
            client.newCall(builder.build()).execute().use { resp ->
                if (resp.code == 401) {
                    throw SecurityException("Memerlukan PIN Pairing")
                }
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code}: ${resp.message}")
                }
                val parsed = printersResAdapter.fromJson(body) ?: CompanionPrintersResponse()
                val protocol = if (useHttps) PrinterProtocol.COMPANION_HTTPS else PrinterProtocol.COMPANION_HTTP
                parsed.printers.map { dto ->
                    PrinterDevice(
                        id = "companion_${host}_${port}_${dto.name}",
                        name = dto.displayName ?: dto.name,
                        host = host,
                        port = port,
                        path = "/api/v1/jobs",
                        protocol = protocol,
                        companionPrinterName = dto.name,
                        pairingToken = token,
                        capabilities = PrinterCapabilities(
                            supportsColor = dto.supportsColor,
                            supportsDuplex = dto.supportsDuplex,
                            supportedPaperIds = dto.paperSizes.ifEmpty { listOf("A4", "F4", "LETTER") },
                            supportedMimeTypes = listOf(
                                "application/pdf",
                                "image/jpeg",
                                "image/png",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                            ),
                            supportedTrays = dto.trays.ifEmpty { listOf("auto", "tray-1") },
                            makeAndModel = "USB via Laptop ($host)",
                            printerStateMessage = "Terhubung ke Companion",
                            attributesProbed = true
                        )
                    )
                }
            }
        }
    }

    suspend fun submitJob(
        printer: PrinterDevice,
        file: File,
        fileName: String,
        mimeType: String,
        options: PrintOptions
    ): Result<CompanionJobResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val optionsJson = JsonConverters.optionsToJson(options)
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("printerName", printer.companionPrinterName ?: printer.name)
                .addFormDataPart("options", optionsJson)
                .addFormDataPart(
                    "file",
                    fileName,
                    file.asRequestBody(mimeType.toMediaType())
                )
                .build()

            val reqBuilder = Request.Builder()
                .url("${printer.companionBaseUrl}/api/v1/jobs")
                .post(multipart)
            if (!printer.pairingToken.isNullOrBlank()) {
                reqBuilder.header("Authorization", "Bearer ${printer.pairingToken}")
            }

            client.newCall(reqBuilder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Companion HTTP ${resp.code}: $body")
                }
                jobResAdapter.fromJson(body) ?: CompanionJobResponse(jobId = "unknown", status = "queued")
            }
        }
    }

    suspend fun getJobStatus(printer: PrinterDevice, jobId: String): Result<CompanionJobResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val reqBuilder = Request.Builder()
                    .url("${printer.companionBaseUrl}/api/v1/jobs/$jobId")
                    .get()
                if (!printer.pairingToken.isNullOrBlank()) {
                    reqBuilder.header("Authorization", "Bearer ${printer.pairingToken}")
                }
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    jobResAdapter.fromJson(body) ?: throw IllegalStateException("Format status invalid")
                }
            }
        }

    suspend fun cancelJob(printer: PrinterDevice, jobId: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                val reqBuilder = Request.Builder()
                    .url("${printer.companionBaseUrl}/api/v1/jobs/$jobId")
                    .delete()
                if (!printer.pairingToken.isNullOrBlank()) {
                    reqBuilder.header("Authorization", "Bearer ${printer.pairingToken}")
                }
                client.newCall(reqBuilder.build()).execute().use { it.isSuccessful }
            }
        }
}
