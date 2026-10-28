package ru.arc.civs.scenarios

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.civs.CivPosition

class RecordedNativeMapFormatTest : FunSpec({
    test("version two preserves observed, opaque and unknown cells separately") {
        val map = RecordedNativeMap.fromResourceRle(
            "/scenarios/terrain-fixture-v2-example.json",
            "2bb9fc0fa79bc3b3499a702408bdca8def7c48080a05489c244abf89f9f21324",
        )
        val observed = CivPosition(10, 64, 20)
        val opaque = CivPosition(11, 64, 20)
        val unknown = CivPosition(10, 64, 21)
        map.states.size shouldBe 7
        map.states[observed] shouldBe "minecraft:stone"
        map.states[opaque] shouldBe "minecraft:stone"
        map.states.containsKey(unknown) shouldBe false
        map.unknownOpaqueIdentity shouldBe setOf(opaque)
        map.loadedChunks shouldBe setOf(0 to 1)
    }

    test("legacy resource format still reads its pinned capture") {
        val map = RecordedNativeMap.fromResourceRle(
            "/scenarios/auto2-forest-roots-surface.json",
            "e21bd41bb477a33fe64a3a80072bff1a366e8755c9889646b586a19c7cd8f291",
        )
        map.states[CivPosition(-1884, 64, -2688)] shouldBe "minecraft:oak_log[axis=y]"
    }
})
