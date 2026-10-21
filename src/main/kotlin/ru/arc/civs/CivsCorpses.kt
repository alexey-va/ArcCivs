package ru.arc.civs

/** Death moves ownership once; rendering and looting use this same saved resident inventory. */
internal object CivsCorpses {
    const val LIFETIME_MILLIS = 5 * 60_000L

    fun create(worker: CivWorker, death: CivDeathContext): CivCorpse {
        require(death.at >= 0 && death.at <= Long.MAX_VALUE - LIFETIME_MILLIS)
        require(worker.cargo == null || worker.cargo.inventoryBacked)
        return CivCorpse(worker.copy(cargo = null), death.position, death.at, death.at + LIFETIME_MILLIS,
            death.killerName?.take(64), death.killerType, death.cause, death.feetY)
    }

    fun validate(city: Settlement) {
        val corpses = city.corpses.orEmpty()
        require(corpses.map { it.worker.id }.distinct().size == corpses.size)
        require(corpses.none { corpse -> city.workers.any { it.id == corpse.worker.id } })
        corpses.forEach { corpse ->
            require(corpse.diedAt >= 0 && corpse.expiresAt > corpse.diedAt &&
                corpse.expiresAt - corpse.diedAt == LIFETIME_MILLIS)
            require(corpse.position.y in -4096..4096)
            require(corpse.feetY == null || corpse.feetY.isFinite() &&
                kotlin.math.floor(corpse.feetY).toInt() == corpse.position.y)
            require(corpse.worker.name.isNotBlank() && corpse.worker.name.length <= 32)
            require(corpse.worker.cargo == null)
            require(corpse.cause.matches(Regex("[A-Z_]{1,64}")))
            require(corpse.killerType == null || corpse.killerType.matches(Regex("[A-Z_]{1,64}")))
            require(corpse.killerName == null || corpse.killerName.length in 1..64 && corpse.killerName.none(Char::isISOControl))
            CivsEquipment.validate(CivsEquipment.slots(corpse.worker))
        }
    }
}
