package com.example.data.local

import com.example.domain.model.PrintOptions
import com.example.domain.model.PrinterCapabilities
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

object JsonConverters {
    val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val optionsAdapter = moshi.adapter(PrintOptions::class.java)
    private val capabilitiesAdapter = moshi.adapter(PrinterCapabilities::class.java)

    fun optionsToJson(options: PrintOptions): String =
        optionsAdapter.toJson(options)

    fun optionsFromJson(json: String?): PrintOptions {
        if (json.isNullOrBlank()) return PrintOptions()
        return runCatching { optionsAdapter.fromJson(json) }.getOrNull() ?: PrintOptions()
    }

    fun capabilitiesToJson(capabilities: PrinterCapabilities): String =
        capabilitiesAdapter.toJson(capabilities)

    fun capabilitiesFromJson(json: String?): PrinterCapabilities {
        if (json.isNullOrBlank()) return PrinterCapabilities()
        return runCatching { capabilitiesAdapter.fromJson(json) }.getOrNull() ?: PrinterCapabilities()
    }
}
