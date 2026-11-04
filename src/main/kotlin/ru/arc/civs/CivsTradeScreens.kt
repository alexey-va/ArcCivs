package ru.arc.civs

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import ru.arc.civs.CivsUi.Companion.COMMIT
import ru.arc.civs.CivsUi.Companion.LAND
import ru.arc.civs.CivsUi.Companion.NAVIGATION
import ru.arc.civs.CivsUi.Companion.PAGE_SIZE
import ru.arc.civs.CivsUi.Companion.STOCK
import ru.arc.paper.menu.DialogTables
import java.util.Locale
import java.util.UUID

internal fun CivsUi.hasTrade(city: Settlement): Boolean = city.pendingDeletion == null &&
    city.inventory?.mode == CivStorageMode.PHYSICAL && runCatching { CivsTrade.market(city) }.isSuccess

/** Public trade never resolves through the management-only selectedCity helper. */
internal fun CivsUi.showTrade(player: Player, cityId: UUID, direction: CivTradeDirection? = null,
    page: Int = 0, feedback: Component? = null) {
    val city = host.settlements().firstOrNull { it.id == cityId }
    val view = try { host.tradeView(player, cityId) } catch (failure: Exception) {
        val market = city?.let { runCatching { CivsTrade.market(it) }.getOrNull() }
        present(player, "civs.trade", "trade.title", listOfNotNull(
            city?.let { body(tint(text.literal(it.name), LAND)) },
            presentation.panel(text.failure(player, failure), DialogTables.PanelSkin.COCOA),
            market?.let { presentation.panel(tr(player, "trade.location", "building" to text.building(player, it.type),
                "coordinates" to coordinates(player, it.entrance)), DialogTables.PanelSkin.NAVY) },
        ), listOf(button(player, "refresh", "trade.refresh", NAVIGATION) { showTrade(player, cityId, direction, page) }),
            reopen = { showTrade(player, cityId, direction, page) }, cityId = cityId)
        return
    }
    val offers = view.offers.filter { direction == null || it.direction == direction }
    val current = page.coerceIn(0, lastPage(offers.size))
    val mode = direction?.name?.lowercase(Locale.ROOT)
    present(player, "civs.trade", "trade.title", listOfNotNull(
        body(tint(text.literal(view.cityName), LAND)),
        presentation.facts(listOf(tr(player, "trade.treasury") to tint(text.literal(view.emeralds), COMMIT)),
            DialogTables.PanelSkin.EVERGREEN),
        presentation.panel(Component.join(Component.newline(), listOfNotNull(
            if (direction == null) tr(player, "trade.help") else tint(tr(player, "trade.$mode-heading"), STOCK),
            tr(player, "trade.bundle-price"),
            if (view.refreshing) tr(player, "trade.refreshing") else if (offers.isEmpty())
                tr(player, if (direction == null) "trade.empty" else "trade.$mode-empty") else null,
        )), DialogTables.PanelSkin.NAVY),
        feedback?.let(::body),
    ), buildList {
        if (direction == null) {
            if (view.offers.any { it.direction == CivTradeDirection.BUY })
                add(button(player, "buy", "trade.buy", STOCK) { showTrade(player, cityId, CivTradeDirection.BUY) })
            if (view.offers.any { it.direction == CivTradeDirection.SELL })
                add(button(player, "sell", "trade.sell", COMMIT) { showTrade(player, cityId, CivTradeDirection.SELL) })
        } else if (!view.refreshing) {
            offers.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, offer ->
                val bundle = tr(player, "trade.bundle", "material" to materialName(player, offer.material), "amount" to offer.amount)
                add(valueButton(player, "offer_$index", "trade.offer", bundle, if (direction == CivTradeDirection.SELL) COMMIT else STOCK,
                    tr(player, "trade.$mode-confirm", "bundle" to bundle)) {
                    perform(player, "civs.trade", "trade.title", { host.trade(player, cityId, offer) },
                        success = { showTrade(player, cityId, direction, current, tint(tr(player, "trade.$mode-complete", "bundle" to bundle), COMMIT)) },
                        failure = { showTrade(player, cityId, direction, current, it) }, workingKey = null)
                })
            }
            pages(player, current, offers.size) { showTrade(player, cityId, direction, it) }
            if (view.offers.any { it.direction != direction })
                add(button(player, "switch", if (direction == CivTradeDirection.BUY) "trade.sell" else "trade.buy", NAVIGATION) {
                    showTrade(player, cityId, if (direction == CivTradeDirection.BUY) CivTradeDirection.SELL else CivTradeDirection.BUY)
                })
        }
        add(button(player, "refresh", "trade.refresh", NAVIGATION) { showTrade(player, cityId, direction, current) })
    }, reopen = { showTrade(player, cityId, direction, current) }, cityId = cityId)
}
