/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
package org.linbible

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive

val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
    explicitNulls = true
}

val prettyJson = Json(json) { prettyPrint = true }

/** Serializes a Kotlin string into a JavaScript/JSON string literal. */
fun jsString(s: String?): String = if (s == null) "null" else JsonPrimitive(s).toString()

fun JsonObjectBuilder.str(key: String, value: String?) = put(key, if (value == null) JsonNull else JsonPrimitive(value))
fun JsonObjectBuilder.num(key: String, value: Number?) = put(key, if (value == null) JsonNull else JsonPrimitive(value))
fun JsonObjectBuilder.bool(key: String, value: Boolean) = put(key, JsonPrimitive(value))
fun JsonObjectBuilder.range(key: String, range: IntRange?) =
    put(key, if (range == null) JsonNull else JsonArray(listOf(JsonPrimitive(range.first), JsonPrimitive(range.last))))
fun JsonObjectBuilder.ints(key: String, values: Collection<Int>) = put(key, JsonArray(values.map { JsonPrimitive(it) }))
fun JsonObjectBuilder.strs(key: String, values: Collection<String>) = put(key, JsonArray(values.map { JsonPrimitive(it) }))
fun JsonObjectBuilder.arr(key: String, values: List<JsonElement>) = put(key, JsonArray(values))
