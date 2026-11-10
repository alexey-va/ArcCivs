package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.LogRecord

class CivsCitizenTurnLoopTest : FunSpec({
    test("consecutive legal local legs can turn and arrive without a stationary rotation warning") {
        residentRuntime(workerId = UUID(0, 42)) { h ->
            val actor = narrowIdleWalker(h)
            val walk = requireNotNull(actor.route?.wanderWalk)
            val warnings = TurnLoopWarnings()
            h.logger.addHandler(warnings)
            try {
                repeat(70) { h.navigate() }
                (walk.legs >= 2) shouldBe true
                h.deliveries shouldBe 0
                h.steps shouldBe emptyList()
                warnings.messages shouldBe emptyList()
            } finally { h.logger.removeHandler(warnings) }
        }
    }

    test("an actor that keeps rotating in place on one route still reports a turn loop") {
        residentRuntime(workerId = UUID(0, 42)) { h ->
            val actor = narrowIdleWalker(h)
            val route = requireNotNull(actor.route)
            val origin = actor.npc.location.point()
            val warnings = TurnLoopWarnings()
            h.logger.addHandler(warnings)
            try {
                repeat(38) { frame ->
                    // Competing headings keep the resident turning away from its eastbound leg.
                    actor.npc.bodyYaw = if (frame % 2 == 0) 40f else 48f
                    h.navigate()
                }
                (actor.route === route) shouldBe true
                actor.npc.location.point() shouldBe origin
                warnings.messages.size shouldBe 1
            } finally { h.logger.removeHandler(warnings) }
        }
    }
})

private fun narrowIdleWalker(h: ResidentRuntime): CitizenActor {
    for ((x, z) in listOf(3 to 0, 4 to -1, 4 to 1, 5 to -1, 5 to 1, 6 to 0))
        for (y in 64..65) h.world.getBlockAt(x, y, z).type = Material.STONE
    val actor = h.actor()
    actor.npc.teleport(Location(h.world, 4.5, 64.0, .5, 90f, 0f))
    h.city = h.city.copy(workers = h.city.workers.map { it.copy(nextDeliveryAt = 2) })
    h.navigationNow = 2_000
    h.owner.pulse(2_000)
    h.navigationNow = 2_500
    h.owner.pulse(2_500)
    h.routeTarget()?.point() shouldBe CitizenPoint(5.5, 64.0, .5)
    return actor
}

private class TurnLoopWarnings : Handler() {
    val messages = mutableListOf<String>()
    override fun publish(record: LogRecord) {
        if (record.message.startsWith("ARCCIVS_NAVIGATION_FAILED ") && "reason=turn-loop" in record.message)
            messages += record.message
    }
    override fun flush() = Unit
    override fun close() = Unit
}
