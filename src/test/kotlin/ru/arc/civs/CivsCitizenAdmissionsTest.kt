package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.HeightMap
import org.bukkit.Material
import org.bukkit.World
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID

class CivsCitizenAdmissionsTest : FunSpec({
    test("buried camp finds loaded native ground above the old height window") {
        spawnFixture(135) { world, city, origin, columns ->
            val selected = spawnSearch(city, world, origin)
            selected.point shouldBe origin.copy(y = 136)
            selected.inspected shouldBe 14 // Original 13 positions, then the first native surface.
            selected.samples.last().support shouldBe "STONE"
            selected.samples.last().feet shouldBe "AIR"
            selected.samples.last().head shouldBe "AIR"
            for (material in listOf(Material.WATER, Material.LAVA, Material.OAK_LOG)) {
                for (column in columns) world.getBlockAt(column.x, 135, column.z).type = material
                spawnSearch(city, world, origin).point shouldBe null
            }
        }
    }

    test("a safe original floor wins below a completed authored roof") {
        spawnFixture(126) { world, city, origin, columns ->
            for (column in columns) world.getBlockAt(column.x, 135, column.z).type = Material.STONE
            val hall = spawnBuilding(BuildingType.TOWN_HALL, roofBounds(origin))
            val selected = spawnSearch(city.copy(buildings = listOf(hall)), world, origin)
            selected.point shouldBe origin
            selected.inspected shouldBe 1
        }
    }

    test("the native surface fallback refuses completed building wall and gate tops") {
        spawnFixture(135) { world, city, origin, _ ->
            for (type in listOf(BuildingType.TOWN_HALL, BuildingType.WALL, BuildingType.GATE)) {
                val covered = city.copy(buildings = listOf(spawnBuilding(type, roofBounds(origin))))
                val rejected = spawnSearch(covered, world, origin)
                rejected.point shouldBe null
                rejected.inspected shouldBe CITIZEN_SPAWN_CANDIDATE_LIMIT
            }
        }
    }

    test("occupied sleeping out-of-city and control positions retain refusal and the bounded probe count") {
        spawnFixture(126) { world, city, origin, columns ->
            val occupied = spawnSearch(city, world, origin, occupied = true)
            occupied.point shouldBe null
            occupied.rejections["occupied"] shouldBe 13
            occupied.inspected shouldBe CITIZEN_SPAWN_CANDIDATE_LIMIT
            occupied.samples.size shouldBe 16
            val sleeping = spawnSearch(city, world, origin, active = false)
            sleeping.point shouldBe null
            sleeping.rejections["sleeping"] shouldBe 13
            val outside = spawnSearch(city.copy(center = origin.copy(x = origin.x + 20), radius = 0), world, origin)
            outside.point shouldBe null
            outside.rejections["outside-city"] shouldBe CITIZEN_SPAWN_CANDIDATE_LIMIT
            val controls = columns.map { point ->
                world.getBlockAt(point.x, point.y, point.z).type = Material.BARREL
                spawnBuilding(BuildingType.HOUSE, CivBox(point, point)).copy(phase = BuildingPhase.SITE, control = point)
            }
            val obstructed = spawnSearch(city.copy(buildings = controls), world, origin)
            obstructed.point shouldBe null
            obstructed.rejections["control-obstructed"] shouldBe 26
        }
    }

    test("unloaded spawn columns are not loaded or read by native height fallback") {
        val world = mockk<World>()
        val origin = CivPosition(8, 127, 8)
        val worldId = UUID.randomUUID()
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns false
        val rejected = spawnSearch(spawnCity(worldId, origin), world, origin)
        rejected.point shouldBe null
        rejected.inspected shouldBe CITIZEN_SPAWN_CANDIDATE_LIMIT
        verify(exactly = 0) { world.getChunkAt(any<Int>(), any<Int>(), any<Boolean>()) }
        verify(exactly = 0) { world.getHighestBlockYAt(any<Int>(), any<Int>(), any<HeightMap>()) }
        verify(exactly = 0) { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) }
    }

    test("a native surface without in-world headroom remains inadmissible") {
        spawnFixture(135) { world, city, origin, columns ->
            for (column in columns) world.getBlockAt(column.x, world.maxHeight - 2, column.z).type = Material.STONE
            spawnSearch(city, world, origin).point shouldBe null
        }
    }
})

private fun spawnSearch(city: Settlement, world: World, origin: CivPosition,
                        active: Boolean = true, occupied: Boolean = false): CitizenSpawnSearch =
    CivsCitizenAdmissions({ _, _, _, _ -> active }, { _, _ -> occupied }).spawnPoint(city, world, origin, 0, emptyList(), 0)

private fun spawnFixture(floorY: Int, body: (CivsWorksiteWorldFixture, Settlement, CivPosition, List<CivPosition>) -> Unit) =
    failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = object : CivsWorksiteWorldFixture("citizen-spawn-ground") {
                // The pinned two-argument WorldMock has a 128 ceiling; the real hill is above it.
                override fun getMaxHeight(): Int = 320
            }.also(paper.server::addWorld)
            val origin = CivPosition(8, 127, 8)
            world.loadChunk(0, 0)
            val columns = citizenSpawnCandidates(origin, 0).take(13).toList()
            for (column in columns) for (y in 64..floorY) world.getBlockAt(column.x, y, column.z).type = Material.STONE
            body(world, spawnCity(world.uid, origin), origin, columns)
        }
    }

private fun spawnCity(world: UUID, origin: CivPosition) = Settlement(UUID.randomUUID(), UUID.randomUUID(),
    "Spawn fixture", world, origin, 16, "test", Stock(), emptyList(), emptyList())

private fun spawnBuilding(type: BuildingType, bounds: CivBox) = CivBuilding(UUID.randomUUID(), type, 1, bounds.min, 0,
    "fixture/spawn-roof", bounds, bounds.min.copy(y = bounds.min.y + 1), BuildingPhase.ACTIVE)

private fun roofBounds(origin: CivPosition) = CivBox(CivPosition(origin.x - 2, 126, origin.z - 2),
    CivPosition(origin.x + 2, 135, origin.z + 2))
