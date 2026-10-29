package ru.arc.civs

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.EquipmentSlot
import ru.arc.paper.npc.PacketPlayerNpc

/** Keep resident scale, visual equipment and threat equipment synchronized. */
internal class CivsCitizenEquipment(
    private val runtime: CitizenRuntime,
    private val threats: CivsResidentThreats,
) {

    fun setEquipment(npc: PacketPlayerNpc, plan: CitizenPlan) {
        npc.setEquipment(EquipmentSlot.HAND, ItemStack(citizenTool(plan) ?: Material.AIR))
    }

    fun syncResident(npc: PacketPlayerNpc, worker: CivWorker, plan: CitizenPlan) {
        npc.sceneLabel = worker.name
        val actor = runtime.actors[worker.id]
        npc.scale = citizenScale(worker)
        val items = CivsEquipment.slots(worker).let { slots ->
            if (CivsResidents.isChild(worker)) slots.mapIndexed { index, item -> if (index in 4..5) null else item } else slots
        }
        if (actor?.equipmentItems != items || actor?.equipmentType != plan.type) {
            val slots = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)
            slots.forEachIndexed { index, slot ->
                npc.setEquipment(slot, if (index == 4 && actor?.tavernService?.loaded == true)
                    ItemStack(Material.valueOf(requireNotNull(actor.tavernService).source.material))
                    else if (index == 4 && actor?.mealVisit?.let { it.foodSource == null || it.servedAt != null } == true)
                    ItemStack(Material.valueOf(requireNotNull(actor.mealVisit).plan.material))
                    else if (index == 4 && actor?.careItem != null) ItemStack(requireNotNull(actor.careItem))
                    else items[index]?.stack() ?: ItemStack(if (index == 4) citizenTool(plan) ?: Material.AIR else Material.AIR))
            }
            actor?.equipmentItems = items
            actor?.equipmentType = plan.type
        }
        threats.equipment(worker.id, items.map { it?.stack() })
    }
}
