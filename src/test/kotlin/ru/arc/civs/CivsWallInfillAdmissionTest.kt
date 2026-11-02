package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

class CivsWallInfillAdmissionTest : FunSpec({
    test("wall infill waits for a failed unaccepted search to retire after its LIFO callbacks") {
        wallAdmissionScenario { s ->
            val background = s.planner.infrastructure(s.f.city)
            val wall = s.infill()
            wall.isDone shouldBe false
            s.loads.size shouldBe 1
            var jobsSeenByEarlierCompletion = -1
            background.whenComplete { _, _ -> jobsSeenByEarlierCompletion = s.planner.debug()["jobs"] as Int }
            s.loads.first().completeExceptionally(CivsRejection("autonomy-no-site"))
            jobsSeenByEarlierCompletion shouldBe 1
            s.loads.size shouldBe 2
            s.planner.debug()["jobs"] shouldBe 1
            s.loads.last().complete(s.f.access)
            wall.join().blueprint.building.type shouldBe BuildingType.WALL
            s.planner.debug()["jobs"] shouldBe 0
        }
    }

    test("a successful unaccepted capture retires before the waiting wall starts") {
        wallAdmissionScenario { s ->
            val background = s.infill()
            val wall = s.infill()
            wall.isDone shouldBe false
            s.loads.size shouldBe 1
            s.loads.first().complete(s.f.access)
            background.join().blueprint.building.type shouldBe BuildingType.WALL
            s.deleted.size shouldBe 1
            s.loads.size shouldBe 2
            s.planner.debug()["jobs"] shouldBe 1
            s.loads.last().complete(s.f.access)
            wall.join().blueprint.materials shouldBe mapOf("DARK_OAK_FENCE" to 2L, "STONE_BRICKS" to 26L)
            s.deleted.size shouldBe 2
            s.planner.debug()["jobs"] shouldBe 0
        }
    }

    test("cancelling a waiting wall leaves the existing search alone and never admits the wall") {
        wallAdmissionScenario { s ->
            val background = s.planner.infrastructure(s.f.city)
            val wall = s.infill()
            wall.isDone shouldBe false
            wall.cancel(false) shouldBe true
            background.isDone shouldBe false
            s.loads.first().isCancelled shouldBe false
            s.loads.first().completeExceptionally(CivsRejection("autonomy-no-site"))
            s.loads.size shouldBe 1
            s.planner.debug()["jobs"] shouldBe 0
            wall.isCancelled shouldBe true
        }
    }

    for (close in listOf(false, true))
        test("${if (close) "closing the planner" else "cancelling the preceding search"} cannot start a waiting wall") {
            wallAdmissionScenario { s ->
                val background = s.planner.infrastructure(s.f.city)
                val wall = s.infill()
                wall.isDone shouldBe false
                if (close) s.planner.close() else background.cancel(false)
                background.isCancelled shouldBe true
                s.loads.first().isCancelled shouldBe true
                wall.isCompletedExceptionally shouldBe true
                s.loads.size shouldBe 1
                s.planner.debug()["jobs"] shouldBe 0
            }
        }

    test("cancelling the wall after admission cancels only its own pending native work") {
        wallAdmissionScenario { s ->
            val background = s.planner.infrastructure(s.f.city)
            val wall = s.infill()
            s.loads.first().completeExceptionally(CivsRejection("autonomy-no-site"))
            s.loads.size shouldBe 2
            wall.cancel(false) shouldBe true
            s.loads.last().isCancelled shouldBe true
            s.planner.debug()["jobs"] shouldBe 0
            background.isCancelled shouldBe false
        }
    }

    test("accepted SITE preparation and whole settlement planning retain busy rejection") {
        wallAdmissionScenario { s ->
            val site = CivBuilding(UUID(187, 1), BuildingType.HOUSE, 1, CivPosition(-20, 64, 20), 0, "fixture/house",
                CivBox(CivPosition(-21, 64, 20), CivPosition(-19, 68, 22)), CivPosition(-20, 65, 19), BuildingPhase.SITE)
            val city = CivsRules.placeSite(s.f.city, s.f.city.owner, site)
            val blueprint = CivBlueprint(site, city.worldId, mockk(), emptyList(), emptyList(), byteArrayOf(), emptyMap())
            val accepted = s.planner.prepareExisting(city, blueprint)
            rejection(s.infill(city)) shouldBe "world-busy"
            accepted.isDone shouldBe false
            s.loads.size shouldBe 1
        }
        wallAdmissionScenario { s ->
            val plan = s.planner.plan(s.f.city)
            s.planner.debug()["planning"] shouldBe 1
            rejection(s.infill()) shouldBe "world-busy"
            plan.isDone shouldBe false
        }
    }

    test("two other cities still consume the ordinary global capacity") {
        wallAdmissionScenario { s ->
            val a = s.planner.infrastructure(s.f.city.copy(id = UUID(187, 2)))
            val b = s.planner.infrastructure(s.f.city.copy(id = UUID(187, 3)))
            rejection(s.infill()) shouldBe "world-busy"
            s.loads.size shouldBe 2
            s.planner.debug()["jobs"] shouldBe 2
            a.isDone shouldBe false
            b.isDone shouldBe false
        }
    }
})

private fun rejection(future: CompletableFuture<*>): String =
    requireNotNull(CivsDiagnostics.rejection(shouldThrow<CompletionException> { future.join() })).reason

private fun wallAdmissionScenario(action: (WallAdmissionScenario) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open().use { paper -> WallAdmissionScenario(paper.server.unsafe.dataVersion).use(action) }
}

private class WallAdmissionScenario(dataVersion: Int) : AutoCloseable {
    val f = wallInfillFixture()
    val loads = mutableListOf<CompletableFuture<CivAutonomousAccess>>()
    val deleted = mutableListOf<UUID>()
    private val world = mockk<CivsWorld>()
    private val blueprints = mockk<CivsBlueprints>()
    val planner = CivsAutonomousPlacement(blueprints, world, Executor(Runnable::run),
        loadAccess = { CompletableFuture<CivAutonomousAccess>().also(loads::add) },
        writeCapture = { _, _ -> CompletableFuture.completedFuture(Unit) },
        deleteCapture = { deleted += it; CompletableFuture.completedFuture(true) }, dataVersion = dataVersion)

    init {
        every { world.autonomousHeightField(any(), any(), any(), any()) } returns CompletableFuture.completedFuture(f.surface)
        every { world.captureTerraform(any(), any(), any(), any()) } answers {
            val draft = arg<CivTerraformDraft>(1)
            CompletableFuture.completedFuture(CivTerraformPlan(draft.building.bounds, emptyList(), draft.route, draft.groundY, 0, 0))
        }
        every { world.autonomousRoute(any(), any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(Unit)
        every { blueprints.prepareInfrastructure(any(), any(), any(), any()) } answers {
            val piece = firstArg<CivSettlementPiece>()
            val bytes = arg<ByteArray>(2)
            val snapshot = mockk<ArcSchematicSnapshot> { every { fileSha256 } returns CivsGateRefit.sha256(bytes) }
            val cells = piece.blocks.map { ArcSchematicBlock(ArcSchematicPoint(it.position.x, it.position.y, it.position.z),
                it.after, false, false) }
            CompletableFuture.completedFuture(CivBlueprint(CivsInfrastructure.building(piece), f.city.worldId, snapshot,
                cells, cells, bytes, CivsMaterials.required(f.plan(piece))))
        }
        every { blueprints.autonomousSource(any(), any()) } returns null
        every { blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers { CompletableFuture() }
    }

    fun infill(city: Settlement = f.city): CompletableFuture<CivAutonomousDesign> =
        planner.wallInfill(city, f.gate.id, f.wall.id, emptyList())

    override fun close() = planner.close()
}
