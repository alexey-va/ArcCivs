package ru.arc.civs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PaperPacketDisplays

/** The chair is fixed to the tested ground point for this one rest, with no world entity or block. */
internal class CivsIdleNapRenderer(private val displays: PaperPacketDisplays) {
    fun show(ground: Location, yaw: Float, scale: Double): CivsIdleNapVisual {
        val at = ground.clone().apply { this.yaw = 0f; pitch = 0f }
        val size = scale.toFloat()
        val facing = Quaternionf().rotationY(Math.toRadians(-yaw.toDouble()).toFloat())
        val handles = ArrayList<PacketDisplay>(5)

        fun block(material: Material, offset: Vector3f, dimensions: Vector3f) {
            val part = displays.spawnBlock(at, material.createBlockData())
            handles += part
            configure(part)
            part.transformation = Transformation(facing.transform(offset.mul(size)), Quaternionf(facing),
                dimensions.mul(size), Quaternionf())
        }

        try {
            val seatTop = CIVS_IDLE_NAP_SEAT_TOP.toFloat()
            val underside = seatTop - .09f
            block(Material.SPRUCE_PLANKS, Vector3f(-.30f, underside, -.27f), Vector3f(.60f, .09f, .54f))
            block(Material.DARK_OAK_PLANKS, Vector3f(-.25f, 0f, -.21f), Vector3f(.08f, underside, .42f))
            block(Material.DARK_OAK_PLANKS, Vector3f(.17f, 0f, -.21f), Vector3f(.08f, underside, .42f))
            block(Material.SPRUCE_PLANKS, Vector3f(-.28f, seatTop, -.27f), Vector3f(.56f, .43f, .08f))

            val shoulder = facing.transform(Vector3f(.37f, 1.59f, -.05f).mul(size))
            val origin = at.clone().add(shoulder.x.toDouble(), shoulder.y.toDouble(), shoulder.z.toDouble())
            val cue = displays.spawnText(origin, Component.empty())
            handles += cue
            configure(cue)
            cue.billboard = Display.Billboard.CENTER
            cue.backgroundColor = Color.fromARGB(0)
            cue.isShadowed = true
            cue.transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(.42f * size), Quaternionf())

            var phase = 0
            return CivsIdleNapVisual(pulse = { age ->
                val next = when (age % 64) {
                    in 0..20 -> 1
                    in 21..42 -> 2
                    else -> 0
                }
                if (next != phase) {
                    phase = next
                    cue.teleportDuration = if (next == 0) 0 else 12
                    cue.text(when (next) {
                        1 -> Component.text("zz", NamedTextColor.AQUA)
                        2 -> Component.text("zzz", NamedTextColor.AQUA)
                        else -> Component.empty()
                    })
                    val lift = when (next) { 1 -> .10; 2 -> .24; else -> 0.0 }
                    cue.teleport(origin.clone().add(0.0, lift * scale, 0.0))
                }
            }, close = { handles.forEach(PacketDisplay::remove) })
        } catch (failure: Exception) {
            handles.forEach(PacketDisplay::remove)
            throw failure
        }
    }

    private fun configure(display: PacketDisplay) {
        display.viewRange = .375f
        display.displayWidth = 1.4f
        display.displayHeight = 1.8f
        display.shadowStrength = 0f
    }
}
