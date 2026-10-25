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

    fun summary(rows: List<Pair<Component, Component>>, width: Int = 320): PaperDialogBody =
        if (decoration == null) PaperDialogBody(Component.join(Component.newline(), rows.map { (label, value) ->
            label.append(Component.text(": ")).append(value)
        }), width)
        else DialogTables.filledBody(rows.map { (label, value) -> listOf(label, value) }, null, width, listOf(1, 1))

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
