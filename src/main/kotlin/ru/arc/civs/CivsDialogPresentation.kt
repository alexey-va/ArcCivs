package ru.arc.civs

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import ru.arc.paper.menu.DialogDecoration
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.DialogTextLayout
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.text.TextAlignment

/** Optional text ornaments from the installed dialogue font, with native text fallback. */
internal class CivsDialogPresentation(font: String) {
    init { require(font.isEmpty() || (':' in font && Key.parseable(font))) { "presentation.font must be a namespaced font key or empty" } }
    private val decoration = font.takeIf(String::isNotEmpty)?.let { DialogDecoration(Key.key(it), 0xF9F00, 0xF9F10) }
    private val corners = DialogDecoration.Corners(piece(0xF9100), piece(0xF9101), piece(0xF9102), piece(0xF9103))
    private val rule = DialogDecoration.Divider(piece(0xF9104), piece(0xF9106), piece(0xF9105))

    fun paragraph(text: Component, width: Int = 320): PaperDialogBody = if (decoration == null) PaperDialogBody(text, width)
        else DialogTextLayout.modelBody(text, TextAlignment.LEFT, width)

    fun callout(text: Component, width: Int = 320): PaperDialogBody =
        decoration?.openCorners(text, width, corners) ?: PaperDialogBody(text, width)

    /** One explicit state summary; installations without the pack retain native prose. */
    fun summary(text: Component, width: Int = 320): PaperDialogBody =
        if (decoration == null) PaperDialogBody(text, width)
        else DialogTables.filledBody(listOf(listOf(text)), null, width, listOf(1))

    fun facts(rows: List<Pair<Component, Component>>, skin: DialogTables.PanelSkin,
        layout: DialogTables.FactsLayout = DialogTables.FactsLayout.ROWS, columns: Int = 1, width: Int = 320): PaperDialogBody =
        if (decoration == null) PaperDialogBody(Component.join(Component.newline(), rows.map { (label, value) ->
            label.append(Component.text(": ")).append(value)
        }), width)
        else DialogTables.factsBody(rows, layout, DialogTables.PanelSpec(skin, width, columns, true))

    fun field(icon: DialogDecoration.FieldIcon, label: Component): Component =
        if (decoration == null) label else DialogDecoration.fieldLabel(icon, label)

    fun heading(title: Component, width: Int = 320): PaperDialogBody =
        decoration?.headingBody(title, width) ?: PaperDialogBody(title, width)

    /** Related sections share native body padding while retaining each panel's measured text. */
    fun compact(bodies: List<PaperDialogBody>): PaperDialogBody = PaperDialogBody(
        Component.join(Component.newline(), bodies.map(PaperDialogBody::text)), bodies.maxOfOrNull(PaperDialogBody::width) ?: 320,
    )

    fun progress(label: Component, current: Int, total: Int, value: Component, width: Int = 320): PaperDialogBody =
        decoration?.progressBody(label, current.toDouble(), total.toDouble(), value, width, 12, DialogDecoration.ProgressColor.AMBER)
            ?: PaperDialogBody(label.append(Component.text(": ")).append(value), width)

    fun supplyTable(rows: List<List<Component>>, headers: List<Component>): PaperDialogBody =
        if (decoration == null) PaperDialogBody(Component.join(Component.newline(), (listOf(headers) + rows).map {
            Component.join(Component.text(" · ", CivsText.BODY), it)
        }), 320)
        else DialogTables.filledBody(rows, headers, 320, listOf(5, 2), DialogTables.PanelSkin.NAVY)

    fun divider(width: Int = 320): PaperDialogBody? = decoration?.let { PaperDialogBody(it.divider(width, rule), width) }

    /** IDs and advances from the installed dialogue-ui/library.json; labels stay native. */
    enum class Action(val base: Int, val icon: Int? = null, val iconOnRight: Boolean = false) {
        CONFIRM(0xF9000, 0xF9060), CLOSE(0xF9050), ADD(0xF9008, 0xF9062),
        PREVIOUS(0xF900C, 0xF9063), NEXT(0xF9010, 0xF9064, true), SETTINGS(0xF9024, 0xF9069),
        TAKE(0xF9030, 0xF906C), DELETE(0xF9044, 0xF9071), RESET(0xF9048, 0xF9072),
        START(0xF904C, 0xF9073), BACK(0xF9050, 0xF9063),
        FOUND(0xF9080), UPGRADE(0xF9088),
    }

    fun button(button: PaperDialogButton, action: Action?): PaperDialogButton {
        if (decoration == null && action == Action.BACK) return button.copy(label = Component.text("‹ ").append(button.label))
        if (decoration == null || action == null) return button
        val frame = DialogDecoration.ButtonFrame(DialogDecoration.Glyph(action.base, 16),
            DialogDecoration.Glyph(action.base + 1, 17), DialogDecoration.Glyph(action.base + 3, 15))
        return button.copy(label = decoration.buttonLabel(button.label, button.width, frame,
            action.icon?.let { DialogDecoration.Glyph(it, if (it in setOf(0xF906C, 0xF9071, 0xF9073)) 10 else 11) }, action.iconOnRight))
    }

    private fun piece(point: Int) = DialogDecoration.Glyph(point, 13)
}
