package ru.arc.civs

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import ru.arc.civs.scenarios.pinnedScenarioResource
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/** Public surface/guard replay. The native boundary is observed, not mocked into successful world mutation. */
class CivsAuto2PlanningRegressionTest : FunSpec({
    test("Auto2 finds a dry connected inner plot after its shoreline street frontages are exhausted") {
        Auto2PlanningFixture().use { s ->
            val original = s.city
            val search = s.planner.search(s.city, BuildingType.HOUSE, 1, UUID(0, 102))
            check(search.isDone) { "Detached planner did not finish synchronously" }
            check(s.drafts.isNotEmpty()) { "No dry connected plot reached native capture: ${s.planner.debug()}" }
            val draft = s.drafts.single()
            draft.route.last() shouldBe draft.building.entrance
            (draft.route.size in 1..CivsAutonomousTerraform.MAXIMUM_ROUTE_LENGTH) shouldBe true
            (draft.route.first() in (s.city.buildings.flatMap(CivsAutonomousGeometry::approach) +
                s.access.routes.values.flatten())) shouldBe true
            draft.columns.filterNot { it.preserve }.forEach { c ->
                requireNotNull(s.surface.at(c.x, c.z)).natural shouldBe true
            }
            s.city.buildings.forEach { old ->
                CivsAutonomousGeometry.overlaps(draft.building.bounds, CivsAutonomousGeometry.futureBounds(s.city, old), 2) shouldBe false
            }
            s.access.standing(s.city, draft.building.id).values.none {
                CivsAutonomousGeometry.horizontalContains(draft.building.bounds, it)
            } shouldBe true
            CivsSettlementLayout.acceptsRoute(CivsSettlementLayout.frame(s.city), draft.route) shouldBe true
            (s.planner.debug(s.city.id)["last"] as List<*>).size shouldBe 1
            s.city shouldBe original // Plot selection neither spends stock nor accepts construction.
            println("Auto2 dry plot=${draft.building.anchor} route=${draft.route.size} columns=${draft.columns.size}; " +
                "native capture intentionally stopped, not full native route acceptance")
        }
    }

    test("the same populated city fully surrounded by unavailable water never manufactures a plot or native capture") {
        Auto2PlanningFixture(unavailable = true).use { s ->
            val original = s.city
            val search = s.planner.search(s.city, BuildingType.HOUSE, 1, UUID(0, 103))
            search.isCompletedExceptionally shouldBe true
            s.drafts shouldBe emptyList()
            s.city shouldBe original
            val entry = (s.planner.debug(s.city.id)["last"] as List<*>).single() as Map<*, *>
            entry["outcome"] shouldBe "autonomy-no-site"
            (entry["candidate"] as Int <= CivsAutonomousPlacement.MAXIMUM_OPTIONS) shouldBe true
            entry["surface_columns"] shouldBe 37_249
        }
    }
})

private class Auto2PlanningFixture(unavailable: Boolean = false) : AutoCloseable {
    private val gson = Gson()
    private val geometry = JsonParser.parseString(pinnedScenarioResource("/scenarios/auto2-forest-city-geometry.json",
        "404bb8db3f0c5b70fb64cb810f40ace14a02e45c2d193ae212a353c2d91164a7")).asJsonObject
    private val capture = JsonParser.parseString(pinnedScenarioResource("/scenarios/auto2-planner-shore.json",
        "1d6dd4aebfb3e7de96bd87aa28e5546a9db482d46711eed13ea32cf1f8e8bdf3")).asJsonObject
    private val buildings = geometry.getAsJsonArray("buildings").map { value ->
        val b = value.asJsonObject.deepCopy()
        b.add("bounds", b.remove("actual_bounds")); b.add("schematicId", b.remove("schematic"))
        if (b.getAsJsonArray("transit").isEmpty) b.remove("transit")
        gson.fromJson(b, CivBuilding::class.java)
    }
    private val metadata = geometry.getAsJsonObject("city")
    val city = CivsRules.validate(Settlement(UUID(0, 2), UUID(0, 1), "Auto2 public planning geometry", UUID(0, 3),
        gson.fromJson(metadata["center"], CivPosition::class.java), metadata["radius"].asInt,
        metadata["style"].asString, Stock(128, 128, 1), buildings, emptyList(),
        autonomy = CivAutonomy(layoutHalfWidth = 56)))
    val access = CivAutonomousAccess(capture.getAsJsonArray("accessRoutes").associate { value ->
        val entry = value.asJsonObject
        UUID.fromString(entry["building"].asString) to entry.getAsJsonArray("positions").map { gson.fromJson(it, CivPosition::class.java) }
    })
    val surface = CivTerrainSurface(capture["minHeight"].asInt, capture["maxHeight"].asInt, buildMap {
        var index = 0
        for (run in capture.getAsJsonArray("runs")) {
            val r = run.asJsonArray
            repeat(r[0].asInt) {
                val x = capture["minX"].asInt + index % capture["width"].asInt
                val z = capture["minZ"].asInt + index / capture["width"].asInt
                put(civsTerrainKey(x, z), CivTerrainSurfaceColumn(r[1].asInt, !unavailable && r[3].asBoolean,
                    r[4].asBoolean, r[2].asInt))
                index++
            }
        }
        check(index == capture["width"].asInt * capture["depth"].asInt)
    })
    val drafts = mutableListOf<CivTerraformDraft>()
    private val world = mockk<CivsWorld>()
    private val blueprints = mockk<CivsBlueprints>()
    val planner = CivsAutonomousPlacement(blueprints, world, Executor(Runnable::run),
        loadAccess = { CompletableFuture.completedFuture(access) })
    init {
        every { blueprints.autonomousSource(any(), any()) } returns null
        every { blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            blueprint(arg(3), arg(4), arg(5), arg<Int?>(7) ?: 0, arg(2))
        }
        every { world.autonomousHeightField(any(), any(), any()) } returns CompletableFuture.completedFuture(surface)
        every { world.captureTerraform(any(), any(), any(), any()) } answers {
            drafts += arg<CivTerraformDraft>(1)
            CompletableFuture.failedFuture(CivsRejection("test-observed-native-capture"))
        }
    }
    /** Exact published Resalta family frame, no private authored voxels. All three levels share it. */
    private fun blueprint(id: UUID, worldId: UUID, anchor: CivPosition, rotation: Int, level: Int): CompletableFuture<CivBlueprint> {
        fun point(x: Int, y: Int, z: Int) = CivsBlueprintGeometry.rotate(ArcSchematicPoint(x, y, z), rotation / 90)
            .let { CivPosition(anchor.x + it.x, anchor.y + it.y, anchor.z + it.z) }
        val corners = listOf(point(-10, -1, -16), point(7, 18, -1))
        val b = CivBuilding(id, BuildingType.HOUSE, level, anchor, rotation, "fixture/resalta/level$level",
            CivBox(CivPosition(corners.minOf { it.x }, corners.minOf { it.y }, corners.minOf { it.z }),
                CivPosition(corners.maxOf { it.x }, corners.maxOf { it.y }, corners.maxOf { it.z })),
            point(0, 1, 0), BuildingPhase.CONSTRUCTING, point(1, 1, -1))
        return CompletableFuture.completedFuture(CivBlueprint(b, worldId, mockk<ArcSchematicSnapshot>(), emptyList(), emptyList(), byteArrayOf()))
    }
    override fun close() = planner.close()
}
