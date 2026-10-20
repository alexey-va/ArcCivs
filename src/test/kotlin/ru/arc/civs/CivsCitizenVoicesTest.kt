package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.SoundCategory
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import ru.arc.paper.npc.PacketPlayerNpc
import ru.arc.paper.npc.PacketNpcPose
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsCitizenVoicesTest : FunSpec({
    test("voice identity is stable and the shared tick budget prevents a crowd from speaking together") {
        val first = UUID(0, 0)
        val second = UUID(0, 1)
        citizenVoice(first) shouldBe "male1"
        citizenVoice(second) shouldBe "female1"
        citizenVoice(UUID.fromString(first.toString())) shouldBe citizenVoice(first)
        val clock = CitizenVoiceClock()
        clock.admit(first, 0) shouldBe true
        clock.admit(second, 59) shouldBe false
        clock.admit(second, 60) shouldBe true
        clock.admit(first, 299) shouldBe false
        clock.admit(first, 300) shouldBe true
        clock.admit(first, 460) shouldBe false
    }

    test("only enabled pack events reach nearby human viewers and sleep suppresses speech") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("voices")
            val actor = mockk<PacketPlayerNpc>()
            val viewer = mockk<Player>(relaxed = true)
            val distant = mockk<Player>(relaxed = true)
            var sleeping = false
            every { actor.isValid } returns true
            every { actor.pose } answers { if (sleeping) PacketNpcPose.SLEEPING else PacketNpcPose.STANDING }
            every { actor.location } answers { Location(world, 0.5, 64.0, 0.5) }
            every { actor.eyeHeight } returns 1.6
            every { actor.world } returns world
            for (player in listOf(viewer, distant)) {
                every { player.isOnline } returns true
                every { player.hasMetadata("NPC") } returns false
                every { player.world } returns world
            }
            every { viewer.location } returns Location(world, 4.5, 64.0, 0.5)
            every { distant.location } returns Location(world, 30.5, 64.0, 0.5)
            val config = YamlConfiguration().apply { set("npc-voices.enabled", true) }
            val voices = CivsCitizenVoices(config)
            voices.say(UUID(0, 0), actor, CitizenVoiceEvent.GREET, 0, listOf(viewer, distant))
            verify(exactly = 1) { viewer.playSound(any<Location>(), "arccivs:citizen.male1.greet", SoundCategory.NEUTRAL, 0.85f, 1f) }
            verify(exactly = 0) { distant.playSound(any<Location>(), any<String>(), any<SoundCategory>(), any<Float>(), any<Float>()) }
            sleeping = true
            voices.say(UUID(0, 0), actor, CitizenVoiceEvent.WORK, 2_000, listOf(viewer))
            sleeping = false
            CivsCitizenVoices(YamlConfiguration()).say(UUID(0, 0), actor, CitizenVoiceEvent.WORK, 4_000, listOf(viewer))
            verify(exactly = 0) { viewer.playSound(any<Location>(), "arccivs:citizen.male1.work", SoundCategory.NEUTRAL, 0.85f, 1f) }
            // A click is explicit, so even consecutive clicks and a sleeping resident answer.
            sleeping = true
            repeat(3) { voices.greet(UUID(0, 0), actor, viewer) }
            verify(exactly = 4) { viewer.playSound(any<Location>(), "arccivs:citizen.male1.greet", SoundCategory.NEUTRAL, 0.85f, 1f) }
        }
    }
})
