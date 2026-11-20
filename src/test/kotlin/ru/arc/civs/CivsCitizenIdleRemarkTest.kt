package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.event.player.PlayerQuitEvent

class CivsCitizenIdleRemarkTest : FunSpec({
    test("an idle adult speaks briefly near a viewer and picks a different line after a long pause") {
        residentRuntime { h ->
            val city = h.city
            val actor = h.actor()
            val initial = Bukkit.getCurrentTick()
            val text = CivsText.fromYaml(civsLocaleSource())
            fun pulseAt(tick: Int) {
                h.paper.performTicks((tick - Bukkit.getCurrentTick()).toLong())
                h.navigationNow = 2_000L + 50L * (tick - initial)
                h.owner.pulse(requireNotNull(h.navigationNow))
            }
            fun speech() = h.owner.hologramSnapshots().single().speech
            pulseAt(initial)
            val first = requireNotNull(actor.nextIdleRemarkTick)
            (first - initial in 200..900) shouldBe true
            pulseAt(first - 1)
            speech() shouldBe null
            pulseAt(first)
            val line = requireNotNull(speech())
            line.startsWith("workers.idle-remark.") shouldBe true
            for (key in 0 until 12) {
                val label = "workers.idle-remark.line-$key"
                val ru = plainText(text.render(label, "ru"))
                val en = plainText(text.render(label, "en"))
                (ru.isNotBlank() && en.isNotBlank() && ru != en) shouldBe true
            }
            pulseAt(first + CITIZEN_BUBBLE_TICKS - 1)
            speech() shouldBe line
            pulseAt(first + CITIZEN_BUBBLE_TICKS)
            speech() shouldBe null
            val second = requireNotNull(actor.nextIdleRemarkTick)
            (second - first in 1_200..2_100) shouldBe true
            pulseAt(second)
            (requireNotNull(speech()) != line) shouldBe true
            h.phase() shouldBe CitizenPhase.IDLE
            h.city shouldBe city
            h.deliveries shouldBe 0
        }
    }

    test("idle remarks give way to transfers, hunger and missing supplies and skip an unseen or overdue opportunity") {
        residentRuntime { h ->
            h.background = true
            val actor = h.actor()
            val worker = h.city.workers.single()
            val viewer = h.paper.server.onlinePlayers.single()
            val nearby = actor.npc.location
            fun dueNow() {
                actor.nextIdleRemarkTick = Bukkit.getCurrentTick()
                h.owner.pulse(2_000)
            }
            fun speech() = h.owner.hologramSnapshots().single().speech
            viewer.teleport(nearby.clone().add(25.0, 0.0, 0.0))
            dueNow()
            actor.idleRemark shouldBe null
            viewer.teleport(nearby)
            actor.nextIdleRemarkTick = Bukkit.getCurrentTick() - 41
            h.owner.pulse(2_000)
            actor.idleRemark shouldBe null
            dueNow()
            val line = requireNotNull(speech())
            val transfer = CitizenRemark("workers.transfer.full-bag.line-0", Bukkit.getCurrentTick())
            actor.transferRemark = transfer
            dueNow()
            speech() shouldBe transfer.key
            actor.idleRemark?.key shouldBe line
            actor.transferRemark = null
            h.city = h.city.copy(workers = listOf(worker.copy(hunger = 14)))
            speech() shouldBe null
            actor.idleRemark = null
            dueNow()
            actor.idleRemark shouldBe null
            h.city = h.city.copy(workers = listOf(worker))
            h.assignFarm()
            h.idleReason = "waiting-inputs"
            dueNow()
            actor.plan.idleReason shouldBe "waiting-inputs"
            actor.idleRemark shouldBe null
            speech() shouldBe null
        }
    }

    test("an idle remark ends when its last nearby viewer walks away or quits and does not replay on return") {
        residentRuntime { h ->
            h.background = true
            val actor = h.actor()
            val first = h.paper.server.onlinePlayers.single()
            val second = h.paper.addPlayer("OtherObserver")
            val near = actor.npc.location
            val far = near.clone().add(30.0, 0.0, 0.0)
            first.teleport(near)
            second.teleport(near)
            var now = 2_000L
            fun pulse() = h.owner.pulse(now++)
            fun speech() = h.owner.hologramSnapshots().single().speech
            fun speak(): String {
                actor.nextIdleRemarkTick = Bukkit.getCurrentTick()
                pulse()
                return requireNotNull(speech())
            }

            val firstLine = speak()
            first.teleport(far)
            pulse()
            speech() shouldBe firstLine
            second.teleport(far)
            pulse()
            speech() shouldBe null
            first.teleport(near)
            pulse()
            speech() shouldBe null

            speak()
            h.owner.onQuit(PlayerQuitEvent(first, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED))
            speech() shouldBe null
            second.teleport(near)
            pulse()
            speech() shouldBe null
        }
    }
})
