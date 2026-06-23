package com.tamimarafat.ferngeist.acp.bridge.paseo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Shared JSON codec for the Paseo client protocol.
 *
 * Paseo speaks a custom tagged-union-on-`type` WebSocket protocol. Incoming
 * frames are parsed defensively from [JsonObject] (see [PaseoEventMapper]) so
 * that unknown message/event variants and schema drift never crash the client;
 * outgoing frames are built with `buildJsonObject`.
 */
internal val PaseoJson: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = false
    }

// ---- Defensive accessors over JsonObject ----

internal fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.contentOrNull != null }?.contentOrNull

internal fun JsonObject.boolOrNull(key: String): Boolean? =
    when (val v = this[key]) {
        is JsonPrimitive -> v.booleanOrNull
        else -> null
    }

internal fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.longOrNull?.toInt() }

internal fun JsonObject.longOrNull(key: String): Long? =
    (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.intOrNull?.toLong() }

internal fun JsonObject.doubleOrNull(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.typeTag(): String? = str("type")

/** First non-null result among the given keys — Paseo usage/field names vary by version. */
internal fun JsonObject.firstInt(vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { intOrNull(it) }

internal fun JsonObject.firstDouble(vararg keys: String): Double? =
    keys.firstNotNullOfOrNull { doubleOrNull(it) }

internal fun JsonObject.firstString(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { str(it) }
