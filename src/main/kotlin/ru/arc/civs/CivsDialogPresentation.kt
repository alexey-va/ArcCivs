package ru.arc.civs

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import ru.arc.paper.menu.DialogDecoration
import ru.arc.paper.menu.DialogTextLayout
import ru.arc.paper.menu.PaperDialogBody
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

    fun divider(width: Int = 320): PaperDialogBody? = decoration?.let { PaperDialogBody(it.divider(width, rule), width) }

    private fun piece(point: Int) = DialogDecoration.Glyph(point, 13)
}
