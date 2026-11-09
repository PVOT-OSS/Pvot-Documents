package org.prauga.documents.util

import androidx.core.graphics.ColorUtils
import java.io.File
import java.util.zip.ZipFile

/**
 * An OpenDocument presentation (`.odp`) read for viewing into a [Presentation]: each visible
 * slide's background and shapes where they sit — text frames (titles, outlines, text boxes),
 * pictures, rectangles, ellipses and other custom shapes, lines, groups and tables — with fills,
 * outlines and text styles resolved through the style hierarchy, so placeholders take their sizes
 * from the master's presentation styles. Decorations on the master page are drawn behind each
 * slide. Charts, embedded objects, notes and animations are not.
 *
 * [file] must be a private copy: the [Presentation] keeps it to read pictures from, and deletes
 * it when closed.
 */
fun parseOdp(file: File): Presentation {
    val result = ZipFile(file).use { zip ->
        fun tree(name: String) = zip.getEntry(name)?.let { e -> zip.getInputStream(e).use(::parseXmlTree) }
        val content = tree("content.xml") ?: error("Not an OpenDocument presentation")
        OdpReader(content, tree("styles.xml")).read()
    }
    return Presentation(result.slides, result.width, result.height, file)
}

private class OdpResult(val slides: List<Slide>, val width: Float, val height: Float)

private class OdpReader(private val content: XmlNode, private val stylesXml: XmlNode?) {
    /** Every style by name, from both files (content's automatic styles win on a clash). */
    private val styles = HashMap<String, XmlNode>()
    private val listStyles = HashMap<String, XmlNode>()
    private val pageLayouts = HashMap<String, XmlNode>()
    private val masters = HashMap<String, XmlNode>()
    private val gradients = HashMap<String, XmlNode>()

    init {
        for (root in listOfNotNull(stylesXml, content)) {
            for (section in listOf("office:styles", "office:automatic-styles", "office:master-styles")) {
                val node = root.child(section) ?: continue
                for (s in node.children) {
                    val name = s.attr("style:name") ?: s.attr("draw:name") ?: continue
                    when (s.name) {
                        "style:style", "style:default-style" -> styles[name] = s
                        "text:list-style" -> listStyles[name] = s
                        "style:page-layout" -> pageLayouts[name] = s
                        "style:master-page" -> masters[name] = s
                        "draw:gradient" -> gradients[name] = s
                    }
                }
            }
        }
    }

    /** A property of [element] ("style:graphic-properties"...) for style [name], looked up
     * through its parents. */
    private fun prop(name: String?, element: String, attribute: String): String? {
        var current = name
        var guard = 0
        while (current != null && guard++ < 16) {
            val style = styles[current] ?: return null
            style.child(element)?.attr(attribute)?.let { return it }
            current = style.attr("style:parent-style-name")
        }
        return null
    }

    fun read(): OdpResult {
        val pages = content.at("office:body", "office:presentation")?.children("draw:page").orEmpty()
        val firstMaster = pages.firstOrNull()?.attr("draw:master-page-name")?.let { masters[it] } ?: masters.values.firstOrNull()
        val layout = firstMaster?.attr("style:page-layout-name")?.let { pageLayouts[it] }?.child("style:page-layout-properties")
        val width = odfLengthPt(layout?.attr("fo:page-width")) ?: 793.7f
        val height = odfLengthPt(layout?.attr("fo:page-height")) ?: 446.4f
        var number = 0
        val slides = pages.mapNotNull { page ->
            val pageStyle = page.attr("draw:style-name")
            if (prop(pageStyle, "style:drawing-page-properties", "presentation:visibility") == "hidden") return@mapNotNull null
            number++
            val master = page.attr("draw:master-page-name")?.let { masters[it] }
            val shapes = ArrayList<SlideShape>()
            master?.let { m -> collectShapes(m, shapes, decorationsOnly = true, number) }
            collectShapes(page, shapes, decorationsOnly = false, number)
            Slide(background(pageStyle) ?: background(master?.attr("draw:style-name")), shapes)
        }
        return OdpResult(slides, width, height)
    }

    private fun background(styleName: String?): SlideFill? {
        styleName ?: return null
        return fillOf(styleName, "style:drawing-page-properties")
    }

    private fun fillOf(styleName: String?, element: String): SlideFill? {
        return when (prop(styleName, element, "draw:fill")) {
            "solid" -> hexColor(prop(styleName, element, "draw:fill-color"))?.let { SlideFill.Solid(withOpacity(it, styleName, element)) }
            "gradient" -> prop(styleName, element, "draw:fill-gradient-name")?.let { gradients[it] }?.let { g ->
                val a = hexColor(g.attr("draw:start-color")); val b = hexColor(g.attr("draw:end-color"))
                if (a != null && b != null) SlideFill.Solid(ColorUtils.blendARGB(a, b, 0.5f)) else (a ?: b)?.let { SlideFill.Solid(it) }
            }
            else -> null
        }
    }

    private fun withOpacity(color: Int, styleName: String?, element: String): Int {
        val opacity = prop(styleName, element, "draw:opacity")?.removeSuffix("%")?.toFloatOrNull() ?: return color
        return (color and 0x00FFFFFF) or ((opacity / 100f * 255).toInt().coerceIn(0, 255) shl 24)
    }

    private fun lineOf(styleName: String?): SlideLine? {
        val stroke = prop(styleName, "style:graphic-properties", "draw:stroke") ?: return null
        if (stroke == "none") return null
        val color = hexColor(prop(styleName, "style:graphic-properties", "svg:stroke-color")) ?: 0xFF000000.toInt()
        val width = odfLengthPt(prop(styleName, "style:graphic-properties", "svg:stroke-width"))?.takeIf { it > 0f } ?: HairlinePt
        return SlideLine(color, width)
    }

    private fun collectShapes(container: XmlNode, out: MutableList<SlideShape>, decorationsOnly: Boolean, number: Int) {
        for (node in container.children) {
            // Placeholders on the master are templates for the slides, not drawn.
            if (decorationsOnly && node.attr("presentation:class") != null && node.attr("presentation:placeholder") != "false") continue
            when (node.name) {
                "draw:frame" -> frame(node, number)?.let(out::add)
                "draw:custom-shape", "draw:rect", "draw:ellipse", "draw:polygon", "draw:path" -> shape(node, number)?.let(out::add)
                "draw:line", "draw:connector" -> line(node)?.let(out::add)
                "draw:g" -> collectShapes(node, out, decorationsOnly, number)
            }
        }
    }

    private fun frameOf(node: XmlNode): SlideFrame? {
        val x = odfLengthPt(node.attr("svg:x")) ?: 0f
        val y = odfLengthPt(node.attr("svg:y")) ?: 0f
        val w = odfLengthPt(node.attr("svg:width")) ?: return null
        val h = odfLengthPt(node.attr("svg:height")) ?: return null
        // draw:transform="rotate (r) translate (x y)": the angle is in radians, counter-clockwise.
        val transform = node.attr("draw:transform")
        if (transform != null) {
            val angle = Regex("rotate\\s*\\(\\s*(-?[\\d.]+)").find(transform)?.groupValues?.get(1)?.toFloatOrNull()
            val tx = Regex("translate\\s*\\(\\s*([-\\d.]+\\w*)\\s+([-\\d.]+\\w*)").find(transform)
            if (angle != null && tx != null) {
                val px = odfLengthPt(tx.groupValues[1]) ?: x
                val py = odfLengthPt(tx.groupValues[2]) ?: y
                // The translate places the rotated top-left corner; recover the unrotated frame
                // by rotating that corner back around the shape's centre.
                val rad = angle.toDouble()
                val cx = px + (w / 2) * Math.cos(rad).toFloat() + (h / 2) * Math.sin(rad).toFloat()
                val cy = py - (w / 2) * Math.sin(rad).toFloat() + (h / 2) * Math.cos(rad).toFloat()
                return SlideFrame(cx - w / 2, cy - h / 2, w, h, (-Math.toDegrees(rad)).toFloat())
            }
        }
        return SlideFrame(x, y, w, h)
    }

    private fun frame(node: XmlNode, number: Int): SlideShape? {
        val frame = frameOf(node) ?: return null
        val styleName = node.attr("presentation:style-name") ?: node.attr("draw:style-name")
        node.child("draw:image")?.let { image ->
            val href = image.attr("xlink:href")?.takeIf { !it.contains("://") }?.removePrefix("./") ?: return null
            return SlideShape.Picture(frame, href, description = node.child("svg:desc")?.allText() ?: node.child("svg:title")?.allText())
        }
        node.child("table:table")?.let { return table(it, frame, number) }
        val box = node.child("draw:text-box") ?: return null
        val text = text(box, styleName, number, outline = node.attr("presentation:class") == "outline")
        val fill = fillOf(node.attr("draw:style-name") ?: styleName, "style:graphic-properties")
        val line = lineOf(node.attr("draw:style-name") ?: styleName)
        if (text.paragraphs.none { it.runs.isNotEmpty() } && fill == null && line == null) return null
        return SlideShape.Shape(frame, SlideGeometry.Rectangle, fill, line, text)
    }

    private fun shape(node: XmlNode, number: Int): SlideShape? {
        val frame = frameOf(node) ?: return null
        val styleName = node.attr("draw:style-name") ?: node.attr("presentation:style-name")
        val geometry = when (node.name) {
            "draw:ellipse" -> SlideGeometry.Ellipse
            "draw:rect" -> if (node.attr("draw:corner-radius") != null) SlideGeometry.RoundRectangle else SlideGeometry.Rectangle
            else -> when (node.child("draw:enhanced-geometry")?.attr("draw:type")) {
                "ellipse", "circle" -> SlideGeometry.Ellipse
                "round-rectangle", "round-quadrat" -> SlideGeometry.RoundRectangle
                "isosceles-triangle", "right-triangle" -> SlideGeometry.Triangle
                "right-arrow" -> SlideGeometry.RightArrow
                "chevron", "pentagon-right" -> SlideGeometry.Chevron
                "diamond" -> SlideGeometry.Diamond
                else -> SlideGeometry.Rectangle
            }
        }
        val fill = fillOf(styleName, "style:graphic-properties")
        val line = lineOf(styleName)
        val text = text(node, styleName, number, outline = false).takeIf { t -> t.paragraphs.any { it.runs.isNotEmpty() } }
        if (fill == null && line == null && text == null) return null
        // Text in a shape sits in its middle unless the style says otherwise.
        val explicitAnchor = prop(styleName, "style:graphic-properties", "draw:textarea-vertical-align")
        return SlideShape.Shape(frame, geometry, fill, line, if (explicitAnchor == null) text?.copy(anchor = SlideTextAnchor.Middle) else text)
    }

    private fun line(node: XmlNode): SlideShape? {
        val x1 = odfLengthPt(node.attr("svg:x1")) ?: return null
        val y1 = odfLengthPt(node.attr("svg:y1")) ?: return null
        val x2 = odfLengthPt(node.attr("svg:x2")) ?: return null
        val y2 = odfLengthPt(node.attr("svg:y2")) ?: return null
        val line = lineOf(node.attr("draw:style-name")) ?: SlideLine(0xFF000000.toInt(), HairlinePt)
        return SlideShape.Shape(
            SlideFrame(minOf(x1, x2), minOf(y1, y2), kotlin.math.abs(x2 - x1), kotlin.math.abs(y2 - y1)),
            SlideGeometry.Line, null, line, null,
            flipH = (x2 < x1) != (y2 < y1),
        )
    }

    private fun table(node: XmlNode, frame: SlideFrame, number: Int): SlideShape {
        val rows = node.children.filter { it.name == "table:table-row" } +
            node.children.filter { it.name == "table:table-header-rows" || it.name == "table:table-rows" }.flatMap { it.children("table:table-row") }
        val columns = node.children("table:table-column").sumOf { it.attr("table:number-columns-repeated")?.toIntOrNull() ?: 1 }
            .takeIf { it > 0 } ?: rows.maxOfOrNull { it.children.count { c -> c.name.startsWith("table:") } } ?: 1
        val cells = rows.map { row ->
            row.children.filter { it.name == "table:table-cell" || it.name == "table:covered-table-cell" }.map { cell ->
                val covered = cell.name == "table:covered-table-cell"
                val style = cell.attr("table:style-name")
                SlideTableCell(
                    text = text(cell, style, number, outline = false),
                    fill = (fillOf(style, "style:graphic-properties") as? SlideFill.Solid)?.color
                        ?: hexColor(prop(style, "style:table-cell-properties", "fo:background-color")),
                    colSpan = cell.attr("table:number-columns-spanned")?.toIntOrNull() ?: 1,
                    rowSpan = cell.attr("table:number-rows-spanned")?.toIntOrNull() ?: 1,
                    covered = covered,
                )
            }
        }
        val rowHeight = if (rows.isEmpty()) frame.height else frame.height / rows.size
        return SlideShape.Table(frame, List(columns) { frame.width / columns }, List(rows.size) { rowHeight }, cells)
    }

    // ---- text ----

    private fun text(container: XmlNode, frameStyle: String?, number: Int, outline: Boolean): SlideText {
        val paragraphs = ArrayList<SlideParagraph>()
        readBlocks(container, frameStyle, number, outline, level = -1, listStyle = null, paragraphs)
        val anchor = when (prop(frameStyle, "style:graphic-properties", "draw:textarea-vertical-align")) {
            "middle" -> SlideTextAnchor.Middle
            "bottom" -> SlideTextAnchor.Bottom
            else -> SlideTextAnchor.Top
        }
        fun pad(side: String) = odfLengthPt(prop(frameStyle, "style:graphic-properties", "fo:padding-$side"))
            ?: odfLengthPt(prop(frameStyle, "style:graphic-properties", "fo:padding"))
        return SlideText(
            paragraphs = paragraphs,
            anchor = anchor,
            insetLeft = pad("left") ?: 7.2f, insetTop = pad("top") ?: 3.6f,
            insetRight = pad("right") ?: 7.2f, insetBottom = pad("bottom") ?: 3.6f,
        )
    }

    private fun readBlocks(
        container: XmlNode,
        frameStyle: String?,
        number: Int,
        outline: Boolean,
        level: Int,
        listStyle: String?,
        out: MutableList<SlideParagraph>,
    ) {
        var firstInItem = true
        for (node in container.children) {
            when (node.name) {
                "text:p", "text:h" -> {
                    val bullet = if (level >= 0 && firstInItem) bulletFor(listStyle, level) else null
                    out.add(paragraph(node, frameStyle, number, bullet, level.coerceAtLeast(0), outline))
                    if (level >= 0) firstInItem = false
                }
                "text:list" -> readBlocks(node, frameStyle, number, outline, level + 1, node.attr("text:style-name") ?: listStyle, out)
                "text:list-item", "text:list-header" -> {
                    readBlocks(node, frameStyle, number, outline, level, listStyle, out)
                }
            }
        }
    }

    /** The bullet for [level] of list style [name], or a plain bullet by level. */
    private fun bulletFor(name: String?, level: Int): String? {
        val style = name?.let { listStyles[it] }
        val levelStyle = style?.children?.firstOrNull { (it.attr("text:level")?.toIntOrNull() ?: 1) == level + 1 }
        return when (levelStyle?.name) {
            "text:list-level-style-bullet" -> levelStyle.attr("text:bullet-char")?.let { bulletLabel(it, level) }
            "text:list-level-style-number" -> null
            else -> Bullets[level % Bullets.size]
        }
    }

    private fun paragraph(node: XmlNode, frameStyle: String?, number: Int, bullet: String?, level: Int, outline: Boolean): SlideParagraph {
        val pStyle = node.attr("text:style-name")
        // Outline paragraphs take their size from the master's per-level outline style.
        val levelStyle = if (outline) frameStyle?.let { outlineLevelStyle(it, level) } else null
        val styleChain = listOfNotNull(pStyle, levelStyle, frameStyle)
        fun textProp(attr: String): String? = styleChain.firstNotNullOfOrNull { prop(it, "style:text-properties", attr) }
        fun paraProp(attr: String): String? = styleChain.firstNotNullOfOrNull { prop(it, "style:paragraph-properties", attr) }
        val base = baseRun(::textProp)
        val runs = ArrayList<TextRun>()
        inline(node, base, null, number, runs)
        return SlideParagraph(
            runs = mergeRuns(runs.filter { it.text.isNotEmpty() }),
            align = when (paraProp("fo:text-align")) {
                "center" -> ParagraphAlign.Center
                "end", "right" -> ParagraphAlign.End
                "justify" -> ParagraphAlign.Justify
                else -> ParagraphAlign.Start
            },
            bullet = bullet,
            level = level,
            spaceBeforePt = odfLengthPt(paraProp("fo:margin-top")) ?: 0f,
            emptySizePt = base.sizePt,
            marginLeftPt = odfLengthPt(paraProp("fo:margin-left")) ?: (level * LevelIndentPt),
            indentPt = odfLengthPt(paraProp("fo:text-indent")) ?: 0f,
        )
    }

    /** "Default-outline1" for a frame styled "Default-outline1" at level 0, "...outline3" at
     * level 2: the master's per-level outline styles. */
    private fun outlineLevelStyle(frameStyle: String, level: Int): String? {
        var current: String? = frameStyle
        var guard = 0
        while (current != null && guard++ < 16) {
            val match = Regex("^(.*-outline)\\d$").find(current)
            if (match != null) return "${match.groupValues[1]}${(level + 1).coerceIn(1, 9)}".takeIf { it in styles }
            current = styles[current]?.attr("style:parent-style-name")
        }
        return null
    }

    private fun baseRun(textProp: (String) -> String?) = RunStyle(
        bold = textProp("fo:font-weight").let { it == "bold" || (it?.toIntOrNull() ?: 400) >= 600 },
        italic = textProp("fo:font-style").let { it == "italic" || it == "oblique" },
        underline = textProp("style:text-underline-style").let { it != null && it != "none" },
        strike = textProp("style:text-line-through-style").let { it != null && it != "none" },
        color = hexColor(textProp("fo:color")),
        sizePt = odfLengthPt(textProp("fo:font-size")) ?: DefaultSizePt,
        position = textProp("style:text-position")?.let {
            when {
                it.startsWith("super") || it.startsWith("33%") -> TextPosition.Superscript
                it.startsWith("sub") || it.startsWith("-33%") -> TextPosition.Subscript
                else -> TextPosition.Normal
            }
        } ?: TextPosition.Normal,
    )

    private fun inline(node: XmlNode, style: RunStyle, link: String?, number: Int, out: MutableList<TextRun>) {
        for (c in node.children) {
            when (c.name) {
                XmlNode.TEXT -> out.add(TextRun(c.text.replace(Whitespace, " "), style.copy(link = link, underline = style.underline || link != null)))
                "text:span" -> inline(c, spanStyle(c.attr("text:style-name"), style), link, number, out)
                "text:a" -> inline(c, style, c.attr("xlink:href")?.takeIf { !it.startsWith("#") } ?: link, number, out)
                "text:s" -> out.add(TextRun(" ".repeat(c.attr("text:c")?.toIntOrNull() ?: 1), style))
                "text:tab" -> out.add(TextRun("\t", style))
                "text:line-break" -> out.add(TextRun("\n", style))
                "text:page-number" -> out.add(TextRun(number.toString(), style))
            }
        }
    }

    private fun spanStyle(name: String?, base: RunStyle): RunStyle {
        if (name == null) return base
        fun t(attr: String) = prop(name, "style:text-properties", attr)
        return base.copy(
            bold = t("fo:font-weight")?.let { it == "bold" || (it.toIntOrNull() ?: 400) >= 600 } ?: base.bold,
            italic = t("fo:font-style")?.let { it == "italic" || it == "oblique" } ?: base.italic,
            underline = t("style:text-underline-style")?.let { it != "none" } ?: base.underline,
            strike = t("style:text-line-through-style")?.let { it != "none" } ?: base.strike,
            color = hexColor(t("fo:color")) ?: base.color,
            highlight = t("fo:background-color")?.let { if (it == "transparent") null else hexColor(it) } ?: base.highlight,
            sizePt = odfLengthPt(t("fo:font-size")) ?: t("fo:font-size")?.removeSuffix("%")?.toFloatOrNull()?.let { pct -> base.sizePt?.times(pct / 100f) } ?: base.sizePt,
        )
    }

    private companion object {
        const val DefaultSizePt = 18f
        const val HairlinePt = 0.75f
        const val LevelIndentPt = 24f
        val Whitespace = Regex("[\\n\\r\\t]+")
    }
}
