package ru.arc.civs

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.security.MessageDigest

class CivsMineVersionFreezeTest : FunSpec({
    test("every V2 through V5 mine ordinal and authored floor stays frozen") {
        val fingerprints = sortedMapOf<String, String>()
        for (style in listOf("medievaloak", "nordic", "caledonia")) for (level in 1..3) for (version in 2..5) {
            val mine = spatialBuilding(style, "miner", level, CivPosition(0, 63, 0))
            val plan = requireNotNull(CivsMinePlan.forBuilding(mine, version))
            val source = buildString {
                appendLine("version=$version;bottom=${plan.bottom};center=${plan.center};lowest=${plan.lowest}")
                (listOfNotNull(plan.entranceLanding) + plan.floors).forEach { floor ->
                    appendLine("floor=${floor.index};feet=${floor.feetY};height=${floor.height};cells=${floor.cells};torches=${floor.torches};sections=${floor.sections}")
                }
                plan.steps.forEachIndexed { ordinal, step -> appendLine("$ordinal:$step") }
            }
            fingerprints["$style/$level/v$version"] = MessageDigest.getInstance("SHA-256")
                .digest(source.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        val expected = JsonParser.parseString(requireNotNull(javaClass.getResource("/mine-plan-v2-v5-fingerprints.json"))
            .readText()).asJsonObject.entrySet().associate { it.key to it.value.asString }
        fingerprints shouldBe expected
    }
})
