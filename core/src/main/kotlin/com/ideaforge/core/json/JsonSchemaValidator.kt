package com.ideaforge.core.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A small, dependency-free validator for the subset of JSON Schema (draft 7 style) that the agent
 * schemas use: `type` (single or list), `properties`, `required`, `additionalProperties` (boolean),
 * `items`, `enum`, `minimum`, `maximum`, `minItems`, `maxItems`, `minLength`.
 *
 * Unknown keywords are ignored so prompt authors can add `description` fields freely.
 * Errors carry a JSON-pointer-like path so the retry prompt can tell the model exactly what to fix.
 */
object JsonSchemaValidator {

    data class Error(val path: String, val message: String) {
        override fun toString() = "${path.ifEmpty { "$" }}: $message"
    }

    fun validate(value: JsonElement, schema: JsonObject): List<Error> {
        val errors = mutableListOf<Error>()
        check(value, schema, "", errors)
        return errors
    }

    private fun check(value: JsonElement, schema: JsonObject, path: String, errors: MutableList<Error>) {
        val types = schema["type"]?.let { t ->
            when (t) {
                is JsonArray -> t.map { it.jsonPrimitive.content }
                else -> listOf(t.jsonPrimitive.content)
            }
        }
        if (types != null && types.none { matchesType(value, it) }) {
            errors += Error(path, "expected ${types.joinToString(" or ")}, got ${describe(value)}")
            return
        }

        schema["enum"]?.jsonArray?.let { allowed ->
            if (allowed.none { it == value }) {
                errors += Error(path, "must be one of ${allowed.joinToString()}, got $value")
            }
        }

        when (value) {
            is JsonObject -> checkObject(value, schema, path, errors)
            is JsonArray -> checkArray(value, schema, path, errors)
            is JsonPrimitive -> checkPrimitive(value, schema, path, errors)
            JsonNull -> Unit
        }
    }

    private fun checkObject(value: JsonObject, schema: JsonObject, path: String, errors: MutableList<Error>) {
        val properties = schema["properties"] as? JsonObject
        (schema["required"] as? JsonArray)?.forEach { req ->
            val name = req.jsonPrimitive.content
            if (!value.containsKey(name)) errors += Error(path, "missing required property \"$name\"")
        }
        val additionalAllowed = (schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull ?: true
        for ((key, child) in value) {
            val childSchema = properties?.get(key) as? JsonObject
            if (childSchema != null) {
                check(child, childSchema, "$path/$key", errors)
            } else if (!additionalAllowed) {
                errors += Error(path, "unexpected property \"$key\"")
            }
        }
    }

    private fun checkArray(value: JsonArray, schema: JsonObject, path: String, errors: MutableList<Error>) {
        schema.int("minItems")?.let { if (value.size < it) errors += Error(path, "needs at least $it items, has ${value.size}") }
        schema.int("maxItems")?.let { if (value.size > it) errors += Error(path, "allows at most $it items, has ${value.size}") }
        val itemSchema = schema["items"] as? JsonObject ?: return
        value.forEachIndexed { i, item -> check(item, itemSchema, "$path/$i", errors) }
    }

    private fun checkPrimitive(value: JsonPrimitive, schema: JsonObject, path: String, errors: MutableList<Error>) {
        if (value.isString) {
            schema.int("minLength")?.let {
                if (value.content.length < it) errors += Error(path, "must be at least $it characters")
            }
            return
        }
        val number = value.doubleOrNull ?: return
        schema.double("minimum")?.let { if (number < it) errors += Error(path, "must be >= ${fmt(it)}, got ${fmt(number)}") }
        schema.double("maximum")?.let { if (number > it) errors += Error(path, "must be <= ${fmt(it)}, got ${fmt(number)}") }
    }

    private fun matchesType(value: JsonElement, type: String): Boolean = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "null" -> value is JsonNull
        "string" -> value is JsonPrimitive && value !is JsonNull && value.isString
        "boolean" -> value is JsonPrimitive && value !is JsonNull && !value.isString && value.booleanOrNull != null
        "integer" -> value is JsonPrimitive && value !is JsonNull && !value.isString &&
            (value.longOrNull != null || value.doubleOrNull?.let { it == Math.floor(it) && !it.isInfinite() } == true)
        "number" -> value is JsonPrimitive && value !is JsonNull && !value.isString && value.doubleOrNull != null
        else -> true
    }

    private fun describe(value: JsonElement): String = when (value) {
        is JsonObject -> "object"
        is JsonArray -> "array"
        JsonNull -> "null"
        is JsonPrimitive -> when {
            value.isString -> "string"
            value.booleanOrNull != null -> "boolean"
            else -> "number"
        }
    }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun fmt(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()
}
