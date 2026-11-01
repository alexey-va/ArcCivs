package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CivsAutonomousWaterColumnsTest : FunSpec({
    test("water capture includes the full core and retained gate approaches without expanding the survey") {
        val city = settlementPlanCity().copy(radius = 128)
        val frame = CivsSettlementLayout.frame(city)
        val oldGate = CivBox(frame.core.max.copy(x = frame.core.max.x + 8),
            frame.core.max.copy(x = frame.core.max.x + 9))
        val columns = civsAutonomousWaterColumns(frame, listOf(oldGate, oldGate))
        for (box in listOf(frame.core, oldGate) + frame.gates.map { it.passage })
            for (x in box.min.x..box.max.x) for (z in box.min.z..box.max.z)
                (civsTerrainKey(x, z) in columns) shouldBe true
        (civsTerrainKey(city.center.x + 127, city.center.z) in columns) shouldBe false
        (columns.size <= CivsAutonomousTerraform.MAXIMUM_SURFACE_COLUMNS) shouldBe true
    }
})
