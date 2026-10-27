package com.ideaforge.core.json

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.io.File

class JsonSchemaValidatorTest {

    private val schema = AppJson.parseToJsonElement(
        """
        {"type":"object","required":["name","score","tags"],"additionalProperties":false,
         "properties":{
           "name":{"type":"string","minLength":1},
           "score":{"type":"number","minimum":1,"maximum":10},
           "count":{"type":"integer"},
           "level":{"type":"string","enum":["low","high"]},
           "maybe":{"type":["integer","null"]},
           "tags":{"type":"array","minItems":1,"items":{"type":"string"}}}}
        """,
    ).jsonObject

    private fun errors(json: String) = JsonSchemaValidator.validate(AppJson.parseToJsonElement(json), schema).map { it.toString() }

    @Test fun `valid document has no errors`() {
        assertThat(errors("""{"name":"a","score":5.5,"count":3,"level":"low","maybe":null,"tags":["x"]}""")).isEmpty()
    }

    @Test fun `missing required property is reported`() {
        assertThat(errors("""{"name":"a","tags":["x"]}""")).containsExactly("$: missing required property \"score\"")
    }

    @Test fun `wrong types are reported with paths`() {
        assertThat(errors("""{"name":5,"score":"high","tags":[1]}""")).containsExactly(
            "/name: expected string, got number",
            "/score: expected number, got string",
            "/tags/0: expected string, got number",
        )
    }

    @Test fun `ranges, enums, lengths and item counts are enforced`() {
        assertThat(errors("""{"name":"","score":11,"level":"mid","tags":[]}""")).containsExactly(
            "/name: must be at least 1 characters",
            "/score: must be <= 10, got 11",
            "/level: must be one of \"low\", \"high\", got \"mid\"",
            "/tags: needs at least 1 items, has 0",
        )
    }

    @Test fun `integer rejects fractions but accepts whole doubles`() {
        assertThat(errors("""{"name":"a","score":1,"count":2.5,"tags":["x"]}""")).containsExactly("/count: expected integer, got number")
        assertThat(errors("""{"name":"a","score":1,"count":2.0,"tags":["x"]}""")).isEmpty()
    }

    @Test fun `additionalProperties false rejects unknown keys`() {
        assertThat(errors("""{"name":"a","score":1,"tags":["x"],"extra":true}""")).containsExactly("$: unexpected property \"extra\"")
    }

    @Test fun `extract handles fences and chatter`() {
        val element = JsonExtract.parseObject("Sure! Here it is:\n```json\n{\"a\": 1}\n```")
        assertThat(element.jsonObject["a"].toString()).isEqualTo("1")
    }

    @Test(expected = JsonExtract.ParseException::class)
    fun `extract fails on truncated JSON`() {
        JsonExtract.parseObject("{\"a\": [1, 2")
    }

    @Test fun `every shipped agent schema is itself valid JSON`() {
        val dir = File(System.getProperty("ideaforge.assets"), "schemas")
        val files = dir.listFiles { f -> f.name.endsWith(".schema.json") }!!.toList()
        assertThat(files).hasSize(5)
        files.forEach { AppJson.parseToJsonElement(it.readText()).jsonObject }
    }
}
