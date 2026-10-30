package ru.arc.civs

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import ru.arc.civs.CivsDialogPresentation.Action
import ru.arc.paper.menu.PaperDialogTextInput
import ru.arc.civs.CivsUi.Companion.PAGE_SIZE
import ru.arc.civs.CivsUi.Companion.NAME
import ru.arc.civs.CivsUi.Companion.LAND
import ru.arc.civs.CivsUi.Companion.COMMIT
import ru.arc.civs.CivsUi.Companion.PERSONAL
import ru.arc.civs.CivsUi.Companion.NAVIGATION
import ru.arc.civs.CivsUi.Companion.DANGER

internal fun CivsUi.showEntry(player: Player) {
    when {
        !host.ready() -> present(player, "civs.loading", "common.title",
            host.unavailableReason()?.let { listOf(body(tr(player, "network.lookup-failed").color(DANGER)),
                body(text.reason(player, it))) } ?: listOf(body(player, "common.starting")), buildList {
                if (isAdmin(player)) add(button(player, "admin", "admin.open") { showAdmin(player) })
            }, reopen = { showEntry(player) })
        host.preview(player) != null -> showPreview(player)
        host.city(player) != null -> showOverview(player)
        host.remoteCity(player) != null -> showRemote(player)
        else -> showWelcome(player)
    }
}


internal fun CivsUi.showWelcome(player: Player): Unit { present(player, "civs.welcome", "common.title",
    listOfNotNull(body(player, if (host.foundingAllowed()) "founding.intro" else "network.view-only"), body(player, "city.sleep-rule"),
        if (host.styles().isEmpty()) body(player, "founding.unavailable") else null), buildList {
        if (host.foundingAllowed() && host.styles().isNotEmpty()) add(button(player, "found", "founding.begin", PERSONAL, appearance = Action.FOUND) { showStyles(player) })
        if (isAdmin(player)) add(button(player, "admin", "admin.open") { showAdmin(player) })
    }, reopen = { showEntry(player) }) }


internal fun CivsUi.showRemote(player: Player, feedback: Component? = null) {
    val remote = host.remoteCity(player) ?: return showEntry(player)
    val city = remote.city
    present(player, "civs.remote", "city.title", listOfNotNull(
        body(tint(tr(player, "city.intro", "name" to city.name), LAND)),
        body(tr(player, "network.remote-help", "server" to remote.server.value)),
        facts(player, listOf(
            tr(player, "network.server-label") to text.literal(remote.server.value),
            tr(player, "label.level") to text.literal(city.level()),
            tr(player, "label.radius") to tr(player, "city.radius", "radius" to city.radius),
            tr(player, "label.population") to tr(player, "common.fraction", "value" to city.workers.size, "limit" to CivsRules.residentLimit(city)),
            tr(player, "label.buildings") to text.literal(CivsRules.buildingCount(city)),
            tr(player, "label.construction") to constructionText(player, city),
        )),
        body(player, "network.snapshot-help"), feedback?.let(::body),
    ), listOf(
        valueButton(player, "transfer", "network.transfer", text.literal(remote.server.value), NAVIGATION) {
            perform(player, "civs.remote", "city.title", { host.transferHome(player) },
                success = { showRemote(player, tr(player, "network.transfer-requested", "server" to remote.server.value)) },
                failure = { showRemote(player, it) }, workingKey = "network.connecting")
        },
    ), reopen = { showRemote(player) }, cityId = city.id)
}


internal fun CivsUi.showStyles(player: Player, page: Int = 0, autonomous: Boolean = false) {
    if (autonomous && !isAdmin(player)) return autonomyDenied(player)
    val styles = host.styles()
    val current = page.coerceIn(0, lastPage(styles.size))
    present(player, if (autonomous) "civs.autonomous-styles" else "civs.styles",
        if (autonomous) "autonomy.style-title" else "founding.style-title",
        listOf(body(player, if (autonomous) "autonomy.style-help" else "founding.style-help")),
        buildList {
            styles.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, style ->
                add(valueButton(player, "style_$index", "common.choice", text.style(player, style)) { showName(player, style, autonomous = autonomous) })
            }
            pages(player, current, styles.size) { showStyles(player, it, autonomous) }
        }, reopen = { showStyles(player, current, autonomous) })
}


internal fun CivsUi.showName(player: Player, style: String, initial: String = "", error: Component? = null, autonomous: Boolean = false) {
    if (autonomous && !isAdmin(player)) return autonomyDenied(player)
    var draft = initial
    present(player, if (autonomous) "civs.autonomous-name" else "civs.name",
        if (autonomous) "autonomy.name-title" else "founding.name-title",
        buildList {
            if (error != null) {
                add(body(tr(player, "founding.prepare-failed").color(DANGER)))
                add(body(error))
            } else add(body(player, if (autonomous) "autonomy.name-help" else "founding.name-help"))
            add(body(detail(player, "label.style", text.style(player, style))))
        },
        listOf(button(player, "prepare", if (error == null) "founding.prepare" else "founding.retry", COMMIT, appearance = Action.START) { context ->
            val name = context.text(NAME)?.trim().orEmpty()
            draft = name
            if (autonomous && !isAdmin(player)) autonomyDenied(player)
            else if (name.isBlank()) showName(player, style, name, text.reason(player, "invalid-name"), autonomous)
            else beginSelection(player, {
                if (autonomous) host.beginAutonomousSelection(player, name, style) else host.beginFoundingSelection(player, name, style)
            }) {
                showName(player, style, name, it, autonomous)
            }
        }),
        inputs = listOf(PaperDialogTextInput(NAME, tr(player, "founding.name-label"), initial = initial.take(32), width = 320, maxLength = 32)),
        reopen = { showName(player, style, draft, autonomous = autonomous) },
    )
}


internal fun CivsUi.showPreview(player: Player, error: Component? = null, page: Int = 0) {
    val preview = host.preview(player)
    if (preview == null) {
        if (error == null) showEntry(player)
        else notice(player, "civs.preview", "preview.title", error) { showEntry(player) }
        return
    }
    if (preview.autonomous && !isAdmin(player)) return autonomyDenied(player)
    val materials = if (preview.autonomous) emptyList() else preview.materials.toSortedMap().entries.toList()
    val current = page.coerceIn(0, lastPage(materials.size))
    present(player, "civs.preview", "preview.title", listOfNotNull(
        body(player, if (preview.autonomous) "autonomy.preview-help" else if (preview.movable) "preview.site-help" else "preview.upgrade-help"),
        facts(player, listOf(
            tr(player, "label.building") to buildingName(player, preview.type, preview.level),
            tr(player, "label.style") to text.style(player, preview.style),
            tr(player, "label.city") to text.literal(preview.cityName),
            tr(player, "label.control") to coordinates(player, preview.control),
            tr(player, "label.entrance") to coordinates(player, preview.entrance),
            tr(player, "label.size") to tr(player, "preview.size", "x" to preview.sizeX, "y" to preview.sizeY, "z" to preview.sizeZ),
            tr(player, "label.rotation") to tr(player, "preview.rotation", "value" to preview.rotation),
        )),
        if (materials.isNotEmpty()) table(materials.drop(current * PAGE_SIZE).take(PAGE_SIZE).map { (material, count) ->
            materialName(player, material) to tr(player, "materials.up-to", "amount" to count)
        }) else null,
        if (materials.isNotEmpty()) body(player, "materials.estimate-help") else null,
        if (preview.autonomous) body(player, "autonomy.resources-help") else null,
        body(player, "preview.protected"), error?.let(::body),
    ), buildList {
        add(button(player, "placement", "panel.return", NAVIGATION) { resumePlacement(player) })
        if (preview.autonomous) {
            add(button(player, "villages", "autonomy.open", PERSONAL) { showAutonomous(player) })
        } else if (host.city(player)?.canManage(player.uniqueId) == true) {
            add(button(player, "management", "management.open", PERSONAL) { showManagement(player) })
        }
        pages(player, current, materials.size) { showPreview(player, error, it) }
    }, reopen = { showPreview(player, page = current) })
}
