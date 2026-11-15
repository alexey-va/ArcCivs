package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.block.Block
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.trails.api.TrailsFootTrafficService
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsFootTrafficTest : FunSpec({
    test("accepted road surface registration needs no online actor and ignores foreign or outside-city targets") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("registered-roads")
            val plugin = paper.createSimplePlugin("ArcCivsTrails")
            val trails = paper.createSimplePlugin("Trails")
            val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(3, 64, 0), 0,
                "test", CivBox(CivPosition(2, 64, 0), CivPosition(4, 65, 2)), CivPosition(3, 64, 3), BuildingPhase.ACTIVE)
            val city = CivsRules.found(UUID.randomUUID(), UUID.randomUUID(), "Roads", world.uid,
                CivPosition(0, 64, 0), 16, "nordic", hall, 1_000)
            val api = mockk<TrailsFootTrafficService>()
            every { api.registerSurface(any()) } returns CompletableFuture.completedFuture(true)
            paper.server.servicesManager.register(TrailsFootTrafficService::class.java, api, trails, ServicePriority.Normal)
            val traffic = CivsFootTraffic(plugin) { listOf(city) }
            val at = CivPosition(1, 64, 1)
            world.loadChunk(0, 0)
            world.getBlockAt(1, 64, 1).type = org.bukkit.Material.COARSE_DIRT
            traffic.registerSurface(city.id, at)
            verify(exactly = 1) { api.registerSurface(world.getBlockAt(1, 64, 1)) }
            traffic.registerSurface(city.id, CivPosition(1600, 64, 0))
            world.isChunkLoaded(100, 0) shouldBe false
            traffic.registerSurface(UUID.randomUUID(), at)
            verify(exactly = 1) { api.registerSurface(any()) }
        }
    }

    test("surface speed defaults to one without Trails or with an older provider missing API classes") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("old-trails")
            val plugin = paper.createSimplePlugin("ArcCivsTrails")
            val ground = world.getBlockAt(0, 64, 0)
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
            val instance = type.getConstructor(Plugin::class.java, kotlin.jvm.functions.Function0::class.java)
                .newInstance(plugin, { emptyList<Settlement>() })
            val speed = type.getMethod("invoke", Block::class.java)
            speed.invoke(instance, ground) shouldBe 1.0
            paper.createSimplePlugin("Trails") // Simulates an installed old provider without exposing any API bytecode.
            repeat(2) { speed.invoke(instance, ground) shouldBe 1.0 }
        }
    }
})
