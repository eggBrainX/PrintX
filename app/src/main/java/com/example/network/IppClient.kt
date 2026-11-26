package com.example.network

import com.example.domain.model.DuplexMode
import com.example.domain.model.PaperSize
import com.example.domain.model.PrintOptions
import com.example.domain.model.PrinterCapabilities
import com.example.domain.model.ScaleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class IppPrintResult(
    val success: Boolean,
    val statusCode: Int,
    val statusMessage: String,
    val jobId: Int? = null
)

class IppClient {
    private val requestIdGen = AtomicInteger(100)

    private val client: OkHttpClient by lazy {
        // Local LAN IPPS copiers commonly use self-signed device certificates
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAll, SecureRandom())
        }
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    suspend fun getPrinterAttributes(ippUrl: String): Result<PrinterCapabilities> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = encodeGetPrinterAttributes(ippUrl)
            val responseBytes = postIpp(ippUrl, payload)
            val parsed = decodeIppResponse(responseBytes)
            val attrs = parsed.attributes

            val makeModel = attrs["printer-make-and-model"]?.firstOrNull().orEmpty()
            val stateCode = attrs["printer-state"]?.firstOrNull()?.toIntOrNull() ?: 3
            val stateReason = attrs["printer-state-reasons"]?.firstOrNull().orEmpty()
            val stateMsg = when (stateCode) {
                3 -> "Siap"
                4 -> "Sedang Mencetak"
                5 -> "Berhenti ($stateReason)"
                else -> "Online"
            }

            val colorSupported = attrs["color-supported"]?.firstOrNull()?.toBooleanStrictOrNull() ?: true
            val sidesList = attrs["sides-supported"].orEmpty()
            val supportsDuplex = sidesList.any { it.contains("two-sided", ignoreCase = true) }

            val rawMedia = attrs["media-supported"].orEmpty()
            val mappedPaperIds = rawMedia.mapNotNull { PaperSize.fromPwgName(it)?.id }
                .distinct()
                .ifEmpty { listOf("A4", "F4", "LETTER", "A3", "A5") }

            val mimeTypes = attrs["document-format-supported"].orEmpty()
                .ifEmpty { listOf("application/pdf", "image/jpeg") }

            val trays = attrs["media-source-supported"].orEmpty()
                .ifEmpty { listOf("auto", "tray-1") }

            val finishings = attrs["finishings-supported"].orEmpty()
            val outputBins = attrs["output-bin-supported"].orEmpty()
                .ifEmpty { listOf("auto") }

            val dpis = attrs["printer-resolution-supported"].orEmpty()
                .mapNotNull { it.substringBefore("x").toIntOrNull() }
                .distinct()
                .sorted()
                .ifEmpty { listOf(150, 300, 600) }

            val markerNames = attrs["marker-names"].orEmpty()
            val markerColors = attrs["marker-colors"].orEmpty()
            val markerLevels = attrs["marker-levels"].orEmpty().map { it.toIntOrNull() ?: -1 }
            val markerTypes = attrs["marker-types"].orEmpty()
            val inkSupplies = markerNames.mapIndexed { idx, mName ->
                com.example.domain.model.InkSupplyLevel(
                    name = mName,
                    colorHex = markerColors.getOrNull(idx) ?: "#0F172A",
                    levelPercent = markerLevels.getOrNull(idx) ?: -1,
                    type = markerTypes.getOrNull(idx) ?: "ink"
                )
            }

            val readyMedia = attrs["media-ready"].orEmpty()
                .mapNotNull { PaperSize.fromPwgName(it)?.label ?: it }
                .distinct()

            val stateReasons = attrs["printer-state-reasons"].orEmpty()
                .filter { it.isNotBlank() && !it.equals("none", ignoreCase = true) }

            PrinterCapabilities(
                supportsColor = colorSupported,
                supportsDuplex = supportsDuplex,
                supportedPaperIds = mappedPaperIds,
                supportedMimeTypes = mimeTypes,
                supportedTrays = trays,
                supportedFinishings = finishings,
                supportedDpis = dpis,
                supportedOutputBins = outputBins,
                inkSupplies = inkSupplies,
                readyMedia = readyMedia,
                stateReasons = stateReasons,
                makeAndModel = makeModel,
                printerStateMessage = stateMsg,
                attributesProbed = true,
                lastProbedAt = System.currentTimeMillis()
            )
        }
    }

    suspend fun printJob(
        ippUrl: String,
        documentBytes: ByteArray,
        mimeType: String,
        jobName: String,
        options: PrintOptions,
        capabilities: PrinterCapabilities
    ): Result<IppPrintResult> = withContext(Dispatchers.IO) {
        runCatching {
            val header = encodePrintJobHeader(ippUrl, mimeType, jobName, options, capabilities)
            val fullPayload = ByteArray(header.size + documentBytes.size)
            System.arraycopy(header, 0, fullPayload, 0, header.size)
            System.arraycopy(documentBytes, 0, fullPayload, header.size, documentBytes.size)

            val responseBytes = postIpp(ippUrl, fullPayload)
            val parsed = decodeIppResponse(responseBytes)
            val jobId = parsed.attributes["job-id"]?.firstOrNull()?.toIntOrNull()
            val statusMsg = parsed.attributes["status-message"]?.firstOrNull()
                ?: if (parsed.statusCode <= 0x00FF) "Berhasil dikirim ke antrian printer" else "IPP Error 0x${parsed.statusCode.toString(16)}"

            IppPrintResult(
                success = parsed.statusCode <= 0x00FF,
                statusCode = parsed.statusCode,
                statusMessage = statusMsg,
                jobId = jobId
            )
        }
    }

    suspend fun cancelJob(ippUrl: String, jobId: Int): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)
            dos.writeShort(0x0200) // IPP 2.0
            dos.writeShort(0x0008) // Cancel-Job
            dos.writeInt(requestIdGen.incrementAndGet())
            dos.writeByte(0x01) // operation-attributes-tag
            writeAttr(dos, TAG_CHARSET, "attributes-charset", "utf-8")
            writeAttr(dos, TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en")
            writeAttr(dos, TAG_URI, "printer-uri", ippUrl)
            writeIntAttr(dos, TAG_INTEGER, "job-id", jobId)
            dos.writeByte(0x03) // end-of-attributes-tag
            dos.flush()

            val response = decodeIppResponse(postIpp(ippUrl, baos.toByteArray()))
            response.statusCode <= 0x00FF
        }
    }

    private fun postIpp(ippUrl: String, bodyBytes: ByteArray): ByteArray {
        val request = Request.Builder()
            .url(ippUrl)
            .post(bodyBytes.toRequestBody("application/ipp".toMediaType()))
            .header("Accept", "application/ipp")
            .build()

        client.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful && bytes.isEmpty()) {
                throw IllegalStateException("HTTP ${response.code}: ${response.message}")
            }
            return bytes
        }
    }

    private fun encodeGetPrinterAttributes(ippUrl: String): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeShort(0x0200) // IPP 2.0
        dos.writeShort(0x000B) // Get-Printer-Attributes
        dos.writeInt(requestIdGen.incrementAndGet())
        dos.writeByte(0x01) // operation-attributes-tag
        writeAttr(dos, TAG_CHARSET, "attributes-charset", "utf-8")
        writeAttr(dos, TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en")
        writeAttr(dos, TAG_URI, "printer-uri", ippUrl)
        val requested = listOf(
            "printer-name",
            "printer-make-and-model",
            "printer-state",
            "printer-state-reasons",
            "document-format-supported",
            "media-supported",
            "media-ready",
            "media-type-supported",
            "media-source-supported",
            "color-supported",
            "sides-supported",
            "finishings-supported",
            "printer-resolution-supported",
            "output-bin-supported",
            "marker-names",
            "marker-colors",
            "marker-levels",
            "marker-types"
        )
        requested.forEachIndexed { index, attr ->
            writeAttr(dos, TAG_KEYWORD, if (index == 0) "requested-attributes" else "", attr)
        }
        dos.writeByte(0x03) // end-of-attributes-tag
        dos.flush()
        return baos.toByteArray()
    }

    private fun encodePrintJobHeader(
        ippUrl: String,
        mimeType: String,
        jobName: String,
        options: PrintOptions,
        capabilities: PrinterCapabilities
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeShort(0x0200) // IPP 2.0
        dos.writeShort(0x0002) // Print-Job
        dos.writeInt(requestIdGen.incrementAndGet())

        // Operation attributes
        dos.writeByte(0x01)
        writeAttr(dos, TAG_CHARSET, "attributes-charset", "utf-8")
        writeAttr(dos, TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en")
        writeAttr(dos, TAG_URI, "printer-uri", ippUrl)
        writeAttr(dos, TAG_NAME, "requesting-user-name", "lanprint-android")
        writeAttr(dos, TAG_NAME, "job-name", jobName.take(120))
        writeAttr(dos, TAG_MIME_TYPE, "document-format", mimeType)

        // Job template attributes
        dos.writeByte(0x02)
        writeIntAttr(dos, TAG_INTEGER, "copies", options.copies.coerceIn(1, 999))
        if (options.copies > 1) {
            writeAttr(
                dos,
                TAG_KEYWORD,
                "multiple-document-handling",
                if (options.collate) "separate-documents-collated-copies" else "separate-documents-uncollated-copies"
            )
        }

        // Paper size: if custom or F4 not explicitly supported, encode media-col with exact 1/100 mm
        val paper = options.paperSize
        if (paper.isCustom || (paper.id == "F4" && !capabilities.supportsPaper(paper))) {
            writeMediaColSize(dos, paper.widthHundredthsMm, paper.heightHundredthsMm)
        } else {
            writeAttr(dos, TAG_KEYWORD, "media", paper.pwgMediaName)
        }

        writeAttr(dos, TAG_KEYWORD, "media-type", options.mediaType.ippKeyword)
        if (options.tray.ippKeyword != "auto") {
            writeAttr(dos, TAG_KEYWORD, "media-source", options.tray.ippKeyword)
        }

        writeIntAttr(dos, TAG_ENUM, "orientation-requested", options.orientation.ippValue)

        val scaling = when (options.scaleType) {
            ScaleType.FIT -> "fit"
            ScaleType.FILL -> "fill"
            ScaleType.ACTUAL, ScaleType.PERCENT -> "none"
        }
        writeAttr(dos, TAG_KEYWORD, "print-scaling", scaling)

        if (capabilities.supportsDuplex || options.duplex != DuplexMode.OFF) {
            writeAttr(dos, TAG_KEYWORD, "sides", options.duplex.ippKeyword)
        }

        if (options.pagesPerSheet > 1) {
            writeIntAttr(dos, TAG_INTEGER, "number-up", options.pagesPerSheet)
        }

        writeAttr(dos, TAG_KEYWORD, "print-color-mode", options.colorMode.ippKeyword)
        writeIntAttr(dos, TAG_ENUM, "print-quality", options.quality.ippEnum)
        writeResolutionAttr(dos, "printer-resolution", options.dpi)
        writeAttr(dos, TAG_KEYWORD, "print-content-optimize", options.contentOptimize.ippKeyword)

        if (options.finishing.isNotEmpty()) {
            options.finishing.forEachIndexed { idx, fin ->
                writeIntAttr(dos, TAG_ENUM, if (idx == 0) "finishings" else "", fin.ippEnum)
            }
        }
        if (options.outputBin != "auto") {
            writeAttr(dos, TAG_KEYWORD, "output-bin", options.outputBin)
        }

        dos.writeByte(0x03) // end-of-attributes-tag
        dos.flush()
        return baos.toByteArray()
    }

    private fun writeMediaColSize(dos: DataOutputStream, xDimHundredthsMm: Int, yDimHundredthsMm: Int) {
        dos.writeByte(TAG_BEG_COLLECTION)
        writeShortString(dos, "media-col")
        dos.writeShort(0)

        dos.writeByte(TAG_MEMBER_ATTR_NAME)
        dos.writeShort(0)
        writeShortString(dos, "media-size")

        dos.writeByte(TAG_BEG_COLLECTION)
        dos.writeShort(0)
        dos.writeShort(0)

        dos.writeByte(TAG_MEMBER_ATTR_NAME)
        dos.writeShort(0)
        writeShortString(dos, "x-dimension")
        dos.writeByte(TAG_INTEGER)
        dos.writeShort(0)
        dos.writeShort(4)
        dos.writeInt(xDimHundredthsMm)

        dos.writeByte(TAG_MEMBER_ATTR_NAME)
        dos.writeShort(0)
        writeShortString(dos, "y-dimension")
        dos.writeByte(TAG_INTEGER)
        dos.writeShort(0)
        dos.writeShort(4)
        dos.writeInt(yDimHundredthsMm)

        dos.writeByte(TAG_END_COLLECTION)
        dos.writeShort(0)
        dos.writeShort(0)

        dos.writeByte(TAG_END_COLLECTION)
        dos.writeShort(0)
        dos.writeShort(0)
    }

    private fun writeAttr(dos: DataOutputStream, tag: Int, name: String, value: String) {
        dos.writeByte(tag)
        writeShortString(dos, name)
        writeShortString(dos, value)
    }

    private fun writeIntAttr(dos: DataOutputStream, tag: Int, name: String, value: Int) {
        dos.writeByte(tag)
        writeShortString(dos, name)
        dos.writeShort(4)
        dos.writeInt(value)
    }

    private fun writeResolutionAttr(dos: DataOutputStream, name: String, dpi: Int) {
        dos.writeByte(TAG_RESOLUTION)
        writeShortString(dos, name)
        dos.writeShort(9)
        dos.writeInt(dpi)
        dos.writeInt(dpi)
        dos.writeByte(3) // dots per inch
    }

    private fun writeShortString(dos: DataOutputStream, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        dos.writeShort(bytes.size)
        if (bytes.isNotEmpty()) dos.write(bytes)
    }

    private data class DecodedIppResponse(
        val statusCode: Int,
        val attributes: Map<String, List<String>>
    )

    private fun decodeIppResponse(bytes: ByteArray): DecodedIppResponse {
        if (bytes.size < 8) {
            return DecodedIppResponse(0x0500, emptyMap())
        }
        val dis = DataInputStream(bytes.inputStream())
        dis.readUnsignedShort() // version
        val statusCode = dis.readUnsignedShort()
        dis.readInt() // request-id

        val attrs = LinkedHashMap<String, MutableList<String>>()
        var currentName = ""
        var depth = 0

        while (dis.available() > 0) {
            val tag = dis.readUnsignedByte()
            if (tag == 0x03) break // end-of-attributes
            if (tag in 0x00..0x0F) continue // delimiter group tag

            val nameLen = dis.readUnsignedShort()
            val nameBytes = ByteArray(nameLen)
            readFullySafe(dis, nameBytes)
            val name = String(nameBytes, StandardCharsets.UTF_8)
            if (name.isNotEmpty() && depth == 0) {
                currentName = name
            }

            val valLen = dis.readUnsignedShort()
            val valBytes = ByteArray(valLen)
            readFullySafe(dis, valBytes)

            when (tag) {
                TAG_BEG_COLLECTION -> depth++
                TAG_END_COLLECTION -> if (depth > 0) depth--
                else -> {
                    val valueStr = when (tag) {
                        TAG_INTEGER, TAG_ENUM -> if (valLen == 4) {
                            DataInputStream(valBytes.inputStream()).readInt().toString()
                        } else ""
                        TAG_BOOLEAN -> if (valLen >= 1) (valBytes[0].toInt() != 0).toString() else "false"
                        TAG_RESOLUTION -> if (valLen == 9) {
                            val rdis = DataInputStream(valBytes.inputStream())
                            val x = rdis.readInt()
                            val y = rdis.readInt()
                            "${x}x${y}"
                        } else ""
                        else -> String(valBytes, StandardCharsets.UTF_8)
                    }
                    if (currentName.isNotEmpty() && valueStr.isNotEmpty() && depth == 0) {
                        attrs.getOrPut(currentName) { mutableListOf() }.add(valueStr)
                    }
                }
            }
        }
        return DecodedIppResponse(statusCode, attrs)
    }

    private fun readFullySafe(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read <= 0) break
            offset += read
        }
    }

    companion object {
        private const val TAG_INTEGER = 0x21
        private const val TAG_BOOLEAN = 0x22
        private const val TAG_ENUM = 0x23
        private const val TAG_RESOLUTION = 0x32
        private const val TAG_BEG_COLLECTION = 0x34
        private const val TAG_END_COLLECTION = 0x37
        private const val TAG_NAME = 0x42
        private const val TAG_KEYWORD = 0x44
        private const val TAG_URI = 0x45
        private const val TAG_CHARSET = 0x47
        private const val TAG_NATURAL_LANGUAGE = 0x48
        private const val TAG_MIME_TYPE = 0x49
        private const val TAG_MEMBER_ATTR_NAME = 0x4A
    }
}
