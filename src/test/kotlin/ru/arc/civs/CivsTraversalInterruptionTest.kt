package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.paper.npc.PacketNpcPose

class CivsTraversalInterruptionTest : FunSpec({
    test("ordinary dispatch re-enters the graph from the actual low feet after a cancelled tunnel crossing") {
        residentRuntime { h ->
            h.navigationNow = 2_000L
            val actor = h.actor()
            val routes = h.owner.javaClass.getDeclaredField("routes").run { isAccessible = true; get(h.owner) } as CivsCitizenRoutes
            routes.cancelRoute(actor)
            for (x in 5..11) for (y in 64..76) {
                for (z in listOf(-1, 1)) h.world.getBlockAt(x, y, z).type = Material.STONE
                if (x == 5) h.world.getBlockAt(x, y, 0).type = Material.STONE
            }
            for (x in 7..9) h.world.getBlockAt(x, 65, 0).type = Material.STONE
            val entrance = CivPosition(6, 64, 0)
            actor.npc.teleport(entrance.location(h.world).also { it.yaw = -90f })
            routes.startRoute(actor, h.world, CivPosition(10, 64, 0), 2_000) shouldBe true
            var ticks = 0
            while (actor.npc.location.x < 7.3 && ticks++ < 200) h.navigate()
            withClue("route=${actor.route?.motion?.failure} feet=${actor.npc.location.point()}") {
                actor.traversal shouldBe CitizenRouteAction.CRAWL
                actor.npc.pose shouldBe PacketNpcPose.SWIMMING
            }
            val low = actor.npc.location.point()
            citizenPacketBodyClear(h.world, low, false) shouldBe false
            routes.cancelRoute(actor)
            h.navigate()
            actor.npc.location.point() shouldBe low
            actor.npc.pose shouldBe PacketNpcPose.SWIMMING
            routes.arrivalBodyClear(actor) shouldBe false
            h.owner.residentCollectionFeet(h.workerId) shouldBe null
            routes.startRoute(actor, h.world, entrance, requireNotNull(h.navigationNow)) shouldBe true
            ticks = 0
            while (actor.traversal != null && ticks++ < 200) h.navigate()
            withClue("after replan: ${actor.route?.motion?.failure} ${actor.npc.location.point()}") {
                actor.npc.pose shouldBe PacketNpcPose.STANDING
                actor.traversal shouldBe null
                citizenPacketBodyClear(h.world, actor.npc.location.point(), false) shouldBe true
            }
            routes.cancelRoute(actor)
            routes.startRoute(actor, h.world, CivPosition(10, 64, 0), requireNotNull(h.navigationNow)) shouldBe true
            ticks = 0
            while (actor.npc.location.x < 7.3 && ticks++ < 200) h.navigate()
            actor.traversal shouldBe CitizenRouteAction.CRAWL
            routes.cancelRoute(actor)
            h.owner.pulse(requireNotNull(h.navigationNow))
            actor.route?.crawlExitOnly shouldBe true
            ticks = 0
            while (actor.traversal != null && ticks++ < 200) h.navigate()
            actor.npc.pose shouldBe PacketNpcPose.STANDING
            actor.route shouldBe null
            h.deliveries shouldBe 0
            h.pickups shouldBe 0
        }
    }

    test("an interrupted mantle releases the hook and physically falls free of the partially overlapped cap") {
        residentRuntime { h ->
            h.navigationNow = 2_000L
            val actor = h.actor()
            val routes = h.owner.javaClass.getDeclaredField("routes").run { isAccessible = true; get(h.owner) } as CivsCitizenRoutes
            routes.cancelRoute(actor)
            for (y in 64..66) h.world.getBlockAt(8, y, 0).type = Material.STONE
            val from = Location(h.world, 7.5, 64.0, .5, -90f, 0f)
            val cap = CivPosition(8, 67, 0)
            actor.npc.teleport(from)
            routes.startRoute(actor, h.world, cap, 2_000) shouldBe true
            var ticks = 0
            while (actor.npc.location.x < 7.82 && ticks++ < 350) h.navigate()
            actor.traversal shouldBe CitizenRouteAction.GRAPPLE
            val release = actor.npc.location
            release.y shouldBe 67.0
            routes.cancelRoute(actor)
            routes.arrivalBodyClear(actor) shouldBe false
            actor.supplyPickup = CitizenSupplyPickup(java.util.UUID.randomUUID(), h.city.buildings.single().id, false)
            repeat(5) { h.navigate() }
            actor.npc.location.point() shouldBe release.point()
            actor.supplyPickup = null
            var previous = release
            repeat(35) {
                h.navigate()
                val actual = actor.npc.location
                (actual.distance(previous) < 1.25) shouldBe true
                (actual.y <= previous.y) shouldBe true
                previous = actual
            }
            withClue("released=${release.point()} landed=${previous.point()}") {
                previous.blockX shouldBe 7
                previous.y shouldBe 64.0
                actor.traversal shouldBe null
            }
            h.deliveries shouldBe 0
            h.pickups shouldBe 0
        }
    }
})
