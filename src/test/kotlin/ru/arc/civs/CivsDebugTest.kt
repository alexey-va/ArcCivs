package ru.arc.civs

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsDebugTest : FunSpec({
    test("malformed filters extra arguments and unknown systems return structured errors without reading state") {
        val city = UUID.randomUUID().toString()
        val worker = UUID.randomUUID().toString()
        val cases = listOf(
            listOf("citizens", "not-a-city") to "invalid-city-id",
            listOf("citizens", city, "not-a-worker") to "invalid-worker-id",
            listOf("all", "not-a-city", worker) to "invalid-city-id",
            listOf("all", city, "not-a-worker") to "invalid-worker-id",
            listOf("overview", city, worker, "extra") to "too-many-arguments",
            listOf("unknown") to "unknown-system",
            listOf("") to "unknown-system",
        )
        var requests = 0
        for ((args, reason) in cases) {
            val lines = CivsDebug.lines(args) { _, _, _ -> requests++; emptyMap() }
            lines.single().startsWith("ARCCIVS_DEBUG ") shouldBe true
            val payload = JsonParser.parseString(lines.single().removePrefix("ARCCIVS_DEBUG ")).asJsonObject
            payload["schema"].asInt shouldBe 1
            payload["error"].asString shouldBe reason
            payload["usage"].asString shouldBe "civs debug [all|system] [city UUID] [worker UUID]"
            payload.getAsJsonArray("systems").map { it.asString } shouldBe CivsDebug.systems
            payload.has("state") shouldBe false
            requests shouldBe 0
        }
    }
})
