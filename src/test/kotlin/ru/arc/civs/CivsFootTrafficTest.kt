package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.plugin.ServicePriority
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.trails.api.TrailsFootTrafficService
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsFootTrafficTest : FunSpec({
    test("optional footsteps forward actual movement with an online city member as authority") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("footsteps")
            val plugin = paper.createSimplePlugin("ArcCivsFootsteps")
            val trails = paper.createSimplePlugin("Trails")
            val member = paper.addPlayer("Member")
            member.teleport(Location(world, 2.0, 65.0, 2.0))
            val npc = paper.addPlayer("Resident")
            npc.teleport(Location(world, 1.1, 65.0, 0.5))
            val worker = CivWorker(UUID.randomUUID(), "Resident", null)
            val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(3, 64, 0), 0,
                "test", CivBox(CivPosition(2, 64, 0), CivPosition(4, 65, 2)), CivPosition(3, 64, 3), BuildingPhase.ACTIVE)
            var city = CivsRules.found(UUID.randomUUID(), UUID.randomUUID(), "Footsteps", world.uid,
                CivPosition(0, 64, 0), 16, "nordic", hall, 1_000).copy(members = setOf(member.uniqueId), workers = listOf(worker))
            val api = mockk<TrailsFootTrafficService>()
            every { api.recordStep(any(), any(), any(), any()) } returns CompletableFuture.completedFuture(TrailsFootTrafficService.StepResult.COUNTED)
            paper.server.servicesManager.register(TrailsFootTrafficService::class.java, api, trails, ServicePriority.Normal)
            val traffic = CivsFootTraffic(plugin) { listOf(city) }
            val from = Location(world, 0.9, 65.0, 0.5)
            val to = npc.location
            traffic.record(city.id, worker.id, npc, from, to)
            verify(exactly = 1) { api.recordStep(npc, member, from, to) }
            city = city.copy(workers = emptyList())
            traffic.record(city.id, worker.id, npc, from, to)
            city = city.copy(workers = listOf(worker), members = emptySet())
            traffic.record(city.id, worker.id, npc, from, to)
            verify(exactly = 1) { api.recordStep(any(), any(), any(), any()) }
        }
    }

    test("missing Trails and absent online management cannot submit terrain mutations") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("no-trails")
            val plugin = paper.createSimplePlugin("ArcCivsFootsteps")
            val npc = paper.addPlayer("Resident")
            val calls = mutableListOf<UUID>()
            val traffic = CivsFootTraffic(plugin) { calls += UUID.randomUUID(); emptyList() }
            traffic.record(UUID.randomUUID(), UUID.randomUUID(), npc, Location(world, 0.9, 65.0, 0.5), Location(world, 1.1, 65.0, 0.5))
            calls.size shouldBe 0
        }
    }

    test("optional integration loads and tolerates an older enabled Trails without API classes") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("old-trails")
            val plugin = paper.createSimplePlugin("ArcCivsFootsteps")
            val owner = paper.addPlayer("Owner")
            owner.teleport(Location(world, 0.9, 65.0, 0.5))
            val worker = CivWorker(UUID.randomUUID(), "Resident", null)
            val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(3, 64, 0), 0,
                "test", CivBox(CivPosition(2, 64, 0), CivPosition(4, 65, 2)), CivPosition(3, 64, 3), BuildingPhase.ACTIVE)
            val city = CivsRules.found(UUID.randomUUID(), owner.uniqueId, "Old Trails", world.uid,
                CivPosition(0, 64, 0), 16, "nordic", hall, 1_000).copy(workers = listOf(worker))
            val parent = CivsFootTraffic::class.java.classLoader
            val isolated = object : ClassLoader(parent) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    if (name.startsWith("ru.ruscrafting.trails.api.")) throw ClassNotFoundException(name)
                    if (listOf("ru.arc.civs.CivsFootTraffic", "ru.arc.civs.CivsTrailsAdapter").none {
                            name == it || name.startsWith(it + '$')
                        }) return super.loadClass(name, resolve)
                    val loaded = findLoadedClass(name) ?: parent.getResourceAsStream(name.replace('.', '/') + ".class")!!.use {
                        val bytes = it.readBytes()
                        defineClass(name, bytes, 0, bytes.size)
                    }
                    if (resolve) resolveClass(loaded)
                    return loaded
                }
            }
            val type = isolated.loadClass("ru.arc.civs.CivsFootTraffic")
            val instance = type.getConstructor(org.bukkit.plugin.Plugin::class.java, kotlin.jvm.functions.Function0::class.java)
                .newInstance(plugin, { listOf(city) })
            val record = type.getMethod("record", UUID::class.java, UUID::class.java, org.bukkit.entity.Entity::class.java,
                Location::class.java, Location::class.java)
            record.invoke(instance, city.id, worker.id, owner, owner.location, Location(world, 1.1, 65.0, 0.5))
            paper.createSimplePlugin("Trails") // Simulates an installed old provider without exposing any API bytecode.
            repeat(2) { record.invoke(instance, city.id, worker.id, owner, owner.location, Location(world, 1.1, 65.0, 0.5)) }
            plugin.isEnabled shouldBe true
        }
    }
})
