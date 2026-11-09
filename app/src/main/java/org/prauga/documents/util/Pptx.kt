package org.prauga.documents.util

import androidx.core.graphics.ColorUtils
import java.io.File
import java.util.zip.ZipFile

/**
 * A PowerPoint `.pptx` read for viewing into a [Presentation]: each visible slide with its
 * background and shapes laid out where they sit, resolved through its layout, master and theme —
 * placeholders take their position and text style from the layout and master, colors map through
 * the master's color map, and shapes without their own fill or outline take the theme's. Text
 * keeps its fonts sizes, bold, italic, underline, strike, colors, alignment, bullets and
 * numbering, spacing, and PowerPoint's shrink-to-fit. Pictures (cropped), groups and tables are
 * drawn; charts, SmartArt, video and animations are not.
 *
 * [file] must be a private copy: the [Presentation] keeps it to read pictures from, and deletes
 * it when closed.
 */
fun parsePptx(file: File): Presentation {
    val (slides, size) = ZipFile(file).use { zip -> PptxReader(zip).read() }
    return Presentation(slides, size.first, size.second, file)
}

/** EMU (English Metric Units: 914,400 to the inch, 12,700 to the point) to points. */
private fun emu(value: String?): Float? = value?.toLongOrNull()?.let { it / 12_700f }

/** A part's XML, its relationships (by id, resolved to part paths), and the part's path. */
private class Part(val path: String, val xml: XmlNode, val rels: Map<String, Rel>)

private class Rel(val target: String, val external: Boolean, val type: String)

/** An affine map from a group's child coordinates to the slide's, as nested groups compose. */
private data class Transform(val sx: Float = 1f, val sy: Float = 1f, val dx: Float = 0f, val dy: Float = 0f) {
    fun apply(f: SlideFrame) = SlideFrame(f.x * sx + dx, f.y * sy + dy, f.width * sx, f.height * sy, f.rotation)

    fun then(inner: Transform) = Transform(inner.sx * sx, inner.sy * sy, inner.dx * sx + dx, inner.dy * sy + dy)
}

private class PptxReader(private val zip: ZipFile) {
    private val partCache = HashMap<String, Part?>()

    fun part(path: String): Part? = partCache.getOrPut(path) {
        val entry = zip.getEntry(path) ?: return@getOrPut null
        val xml = zip.getInputStream(entry).use(::parseXmlTree)
        Part(path, xml, rels(path))
    }

    private fun rels(path: String): Map<String, Rel> {
        val dir = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val relsPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/$name.rels"
        val entry = zip.getEntry(relsPath) ?: return emptyMap()
        val xml = zip.getInputStream(entry).use(::parseXmlTree)
        return xml.children("Relationship").mapNotNull { r ->
            val id = r.attr("Id") ?: return@mapNotNull null
            val target = r.attr("Target") ?: return@mapNotNull null
            val external = r.attr("TargetMode") == "External"
            id to Rel(if (external) target else resolvePath(dir, target), external, r.attr("Type").orEmpty())
        }.toMap()
    }

    fun read(): Pair<List<Slide>, Pair<Float, Float>> {
        val presentation = part("ppt/presentation.xml") ?: error("Not a PowerPoint presentation")
        val size = presentation.xml.child("p:sldSz")
        val width = emu(size?.attr("cx")) ?: 720f
        val height = emu(size?.attr("cy")) ?: 540f
        val defaultText = presentation.xml.child("p:defaultTextStyle")
        val slides = presentation.xml.child("p:sldIdLst")?.children("p:sldId").orEmpty().mapNotNull { id ->
            val rel = presentation.rels[id.attr("r:id")] ?: return@mapNotNull null
            val slide = part(rel.target) ?: return@mapNotNull null
            if (slide.xml.attr("show") == "0") return@mapNotNull null
            runCatching { SlideBuilder(this, slide, defaultText).build() }.getOrNull()
        }
        // Slide numbers count only the slides that are shown.
        return slides.mapIndexed { i, s -> s.withSlideNumber(i + 1) } to (width to height)
    }
}

/** "../media/image1.png" against "ppt/slides" is "ppt/media/image1.png". */
private fun resolvePath(dir: String, target: String): String {
    if (target.startsWith("/")) return target.drop(1)
    val parts = ArrayList(dir.split('/').filter { it.isNotEmpty() })
    for (piece in target.split('/')) {
        when (piece) {
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            ".", "" -> {}
            else -> parts.add(piece)
        }
    }
    return parts.joinToString("/")
}

/** A slide built before its number is known; slide-number fields are filled in afterwards. */
private class PendingSlide(val background: SlideFill?, val shapes: List<SlideShape>) {
    fun withSlideNumber(n: Int): Slide = Slide(background, shapes.map { it.withNumber(n) })
}

private fun SlideShape.withNumber(n: Int): SlideShape = when (this) {
    is SlideShape.Shape -> copy(text = text?.withNumber(n))
    is SlideShape.Table -> copy(cells = cells.map { row -> row.map { it.copy(text = it.text.withNumber(n)) } })
    is SlideShape.Picture -> this
}

private fun SlideText.withNumber(n: Int) = copy(
    paragraphs = paragraphs.map { p -> p.copy(runs = p.runs.map { if (it.text == SlideNumberMark) it.copy(text = n.toString()) else it }) }
)

/** Stands in for a slide-number field until the slide's number is known. */
private const val SlideNumberMark = "\u0000#"

/** Resolves one slide against its layout, master and theme. */
private class SlideBuilder(private val reader: PptxReader, private val slide: Part, private val defaultText: XmlNode?) {
    private val layout: Part? = slide.rels.values.firstOrNull { it.type.endsWith("/slideLayout") }?.let { reader.part(it.target) }
    private val master: Part? = layout?.rels?.values?.firstOrNull { it.type.endsWith("/slideMaster") }?.let { reader.part(it.target) }
    private val theme: Part? = master?.rels?.values?.firstOrNull { it.type.endsWith("/theme") }?.let { reader.part(it.target) }

    private val themeColors: Map<String, Int> = theme?.xml?.find("a:clrScheme")?.children
        ?.filter { it.name != XmlNode.TEXT }
        ?.mapNotNull { c -> c.children.firstOrNull { it.name != XmlNode.TEXT }?.let { baseColor(it, emptyMap()) }?.let { c.name.removePrefix("a:") to it } }
        ?.toMap().orEmpty()

    /** The master's color map ("bg1" -> "lt1"), overridden by a slide or layout that brings its
     * own. */
    private val colorMap: Map<String, String> = run {
        val override = sequenceOf(slide, layout).firstNotNullOfOrNull { it?.xml?.at("p:clrMapOvr", "a:overrideClrMapping") }
        val map = override ?: master?.xml?.child("p:clrMap")
        val defaults = mapOf("bg1" to "lt1", "tx1" to "dk1", "bg2" to "lt2", "tx2" to "dk2")
        defaults + (map?.let { m ->
            listOf("bg1", "tx1", "bg2", "tx2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")
                .mapNotNull { k -> m.attr(k)?.let { k to it } }.toMap()
        } ?: emptyMap())
    }

    fun build(): PendingSlide {
        val shapes = ArrayList<SlideShape>()
        val showMaster = slide.xml.attr("showMasterSp") != "0" && layout?.xml?.attr("showMasterSp") != "0"
        if (showMaster) master?.let { shapes.addAll(tree(it, decorationsOnly = true)) }
        layout?.let { shapes.addAll(tree(it, decorationsOnly = true)) }
        shapes.addAll(tree(slide, decorationsOnly = false))
        return PendingSlide(background(), shapes)
    }

    private fun background(): SlideFill? {
        for (part in listOfNotNull(slide, layout, master)) {
            val bg = part.xml.at("p:cSld", "p:bg") ?: continue
            bg.child("p:bgPr")?.let { pr -> fill(pr, part)?.let { return it } }
            bg.child("p:bgRef")?.let { ref -> color(ref)?.let { return SlideFill.Solid(it) } }
        }
        return null
    }

    // ---- colors ----

    /** The color element inside [parent] (solidFill, a color reference...), resolved to ARGB. */
    fun color(parent: XmlNode?): Int? {
        val c = parent?.children?.firstOrNull { it.name in ColorElements } ?: return null
        return baseColor(c, colorMap)?.let { applyModifiers(it, c) }
    }

    private fun baseColor(c: XmlNode, map: Map<String, String>): Int? = when (c.name) {
        "a:srgbClr" -> c.attr("val")?.let(::hexColor)
        "a:sysClr" -> (c.attr("lastClr") ?: if (c.attr("val") == "window") "FFFFFF" else "000000").let(::hexColor)
        "a:schemeClr" -> c.attr("val")?.let { name ->
            val mapped = map[name] ?: name
            themeColors[mapped] ?: themeColors[name]
        }
        "a:prstClr" -> PresetColors[c.attr("val")]
        "a:scrgbClr" -> {
            fun pct(n: String) = ((c.attr(n)?.toIntOrNull() ?: 0) / 100_000f * 255).toInt().coerceIn(0, 255)
            0xFF000000.toInt() or (pct("r") shl 16) or (pct("g") shl 8) or pct("b")
        }
        else -> null
    }

    /** DrawingML's color modifiers: lightness scaled and offset, tints toward white, shades
     * toward black, and transparency. */
    private fun applyModifiers(argb: Int, c: XmlNode): Int {
        var color = argb
        var alpha = 255
        val hsl = FloatArray(3)
        for (m in c.children) {
            val v = (m.attr("val")?.toIntOrNull() ?: continue) / 100_000f
            when (m.name) {
                "a:lumMod" -> { ColorUtils.colorToHSL(color, hsl); hsl[2] = (hsl[2] * v).coerceIn(0f, 1f); color = ColorUtils.HSLToColor(hsl) }
                "a:lumOff" -> { ColorUtils.colorToHSL(color, hsl); hsl[2] = (hsl[2] + v).coerceIn(0f, 1f); color = ColorUtils.HSLToColor(hsl) }
                "a:tint" -> color = ColorUtils.blendARGB(0xFFFFFFFF.toInt(), color, v)
                "a:shade" -> color = ColorUtils.blendARGB(0xFF000000.toInt(), color, v)
                "a:satMod" -> { ColorUtils.colorToHSL(color, hsl); hsl[1] = (hsl[1] * v).coerceIn(0f, 1f); color = ColorUtils.HSLToColor(hsl) }
                "a:alpha" -> alpha = (v * 255).toInt().coerceIn(0, 255)
            }
        }
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    /** A fill from a shape's properties: solid, the first stop of a gradient, a pattern's
     * foreground, or a picture. Null when there's none; [NoFill] when it's explicitly empty. */
    private fun fill(spPr: XmlNode?, part: Part): SlideFill? {
        if (spPr == null) return null
        spPr.child("a:solidFill")?.let { f -> return color(f)?.let { SlideFill.Solid(it) } }
        spPr.child("a:gradFill")?.let { g ->
            val stops = g.child("a:gsLst")?.children("a:gs").orEmpty().mapNotNull { color(it) }
            if (stops.isNotEmpty()) return SlideFill.Solid(if (stops.size == 1) stops[0] else ColorUtils.blendARGB(stops.first(), stops.last(), 0.5f))
        }
        spPr.child("a:pattFill")?.let { pf -> return color(pf.child("a:fgClr"))?.let { SlideFill.Solid(it) } }
        spPr.child("a:blipFill")?.let { bf ->
            val rel = part.rels[bf.child("a:blip")?.attr("r:embed")] ?: return null
            if (!rel.external) return SlideFill.Picture(rel.target)
        }
        if (spPr.child("a:noFill") != null) return NoFill
        return null
    }

    // ---- shape tree ----

    private fun tree(part: Part, decorationsOnly: Boolean): List<SlideShape> {
        val root = part.xml.at("p:cSld", "p:spTree") ?: return emptyList()
        val out = ArrayList<SlideShape>()
        walk(root, part, Transform(), decorationsOnly, out)
        return out
    }

    private fun walk(container: XmlNode, part: Part, transform: Transform, decorationsOnly: Boolean, out: MutableList<SlideShape>) {
        for (node in container.children) {
            when (node.name) {
                "p:sp", "p:cxnSp" -> {
                    // Placeholders on a master or layout are templates, not drawn.
                    if (decorationsOnly && node.find("p:ph") != null) continue
                    shape(node, part, transform)?.let(out::add)
                }
                "p:pic" -> {
                    if (decorationsOnly && node.find("p:ph") != null) continue
                    picture(node, part, transform)?.let(out::add)
                }
                "p:graphicFrame" -> table(node, part, transform)?.let(out::add)
                "p:grpSp" -> {
                    val xfrm = node.at("p:grpSpPr", "a:xfrm")
                    val inner = groupTransform(xfrm)
                    walk(node, part, transform.then(inner), decorationsOnly, out)
                }
                "mc:AlternateContent" -> node.child("mc:Choice")?.let { walk(it, part, transform, decorationsOnly, out) }
            }
        }
    }

    private fun groupTransform(xfrm: XmlNode?): Transform {
        if (xfrm == null) return Transform()
        val off = xfrm.child("a:off"); val ext = xfrm.child("a:ext")
        val chOff = xfrm.child("a:chOff"); val chExt = xfrm.child("a:chExt")
        val x = emu(off?.attr("x")) ?: 0f; val y = emu(off?.attr("y")) ?: 0f
        val cx = emu(ext?.attr("cx")) ?: 0f; val cy = emu(ext?.attr("cy")) ?: 0f
        val chx = emu(chOff?.attr("x")) ?: x; val chy = emu(chOff?.attr("y")) ?: y
        val chcx = emu(chExt?.attr("cx"))?.takeIf { it > 0f } ?: cx.takeIf { it > 0f } ?: 1f
        val chcy = emu(chExt?.attr("cy"))?.takeIf { it > 0f } ?: cy.takeIf { it > 0f } ?: 1f
        val sx = if (cx > 0f) cx / chcx else 1f
        val sy = if (cy > 0f) cy / chcy else 1f
        return Transform(sx, sy, x - chx * sx, y - chy * sy)
    }

    private fun frame(xfrm: XmlNode?): SlideFrame? {
        val off = xfrm?.child("a:off") ?: return null
        val ext = xfrm.child("a:ext") ?: return null
        return SlideFrame(
            emu(off.attr("x")) ?: 0f, emu(off.attr("y")) ?: 0f,
            emu(ext.attr("cx")) ?: 0f, emu(ext.attr("cy")) ?: 0f,
            (xfrm.attr("rot")?.toFloatOrNull() ?: 0f) / 60_000f,
        )
    }

    /** A placeholder's type and index, and the matching placeholders on the layout and master it
     * inherits from. */
    private inner class Placeholder(ph: XmlNode) {
        val type: String = ph.attr("type") ?: "body"
        private val idx: String? = ph.attr("idx")
        val onLayout: XmlNode? = layout?.let { find(it, layoutOnly = true) }
        val onMaster: XmlNode? = master?.let { find(it, layoutOnly = false) }

        /** On a layout, the placeholder with the same index, else the same type; on the master,
         * the one of the same broad type (a subtitle inherits from the body placeholder). */
        private fun find(part: Part, layoutOnly: Boolean): XmlNode? {
            val shapes = ArrayList<XmlNode>()
            collect(part.xml.at("p:cSld", "p:spTree"), shapes)
            fun typeOf(n: XmlNode) = n.find("p:ph")?.attr("type") ?: "body"
            if (layoutOnly) {
                if (idx != null) shapes.firstOrNull { it.find("p:ph")?.attr("idx") == idx }?.let { return it }
                return shapes.firstOrNull { typeOf(it) == type }
            }
            return shapes.firstOrNull { masterType(typeOf(it)) == masterType(type) }
        }

        private fun collect(node: XmlNode?, out: MutableList<XmlNode>) {
            node ?: return
            for (c in node.children) {
                if ((c.name == "p:sp" || c.name == "p:pic") && c.find("p:ph") != null) out.add(c)
                else if (c.name == "p:grpSp") collect(c, out)
            }
        }

        /** Which of the master's text styles applies. */
        val textStyle: String = when (type) {
            "title", "ctrTitle" -> "p:titleStyle"
            "body", "subTitle", "obj" -> "p:bodyStyle"
            else -> "p:otherStyle"
        }
    }

    private fun masterType(type: String): String = when (type) {
        "ctrTitle" -> "title"
        "subTitle", "obj" -> "body"
        else -> type
    }

    private fun shape(node: XmlNode, part: Part, transform: Transform): SlideShape? {
        val ph = node.find("p:ph")?.let { Placeholder(it) }
        val spPr = node.child("p:spPr")
        val xfrm = spPr?.child("a:xfrm") ?: ph?.onLayout?.at("p:spPr", "a:xfrm") ?: ph?.onMaster?.at("p:spPr", "a:xfrm")
        val frame = transform.apply(frame(xfrm) ?: return null)
        val style = node.child("p:style")
        val geometry = when (val prst = spPr?.child("a:prstGeom")?.attr("prst")) {
            null, "rect" -> if (node.name == "p:cxnSp") SlideGeometry.Line else SlideGeometry.Rectangle
            "roundRect", "snipRoundRect", "round2SameRect" -> SlideGeometry.RoundRectangle
            "ellipse", "circle" -> SlideGeometry.Ellipse
            "line", "straightConnector1" -> SlideGeometry.Line
            "triangle", "rtTriangle" -> SlideGeometry.Triangle
            "rightArrow" -> SlideGeometry.RightArrow
            "chevron", "homePlate" -> SlideGeometry.Chevron
            "diamond" -> SlideGeometry.Diamond
            else -> if (prst.startsWith("bentConnector") || prst.startsWith("curvedConnector")) SlideGeometry.Line else SlideGeometry.Rectangle
        }
        val ownFill = fill(spPr, part)
        val fill = when {
            ownFill === NoFill -> null
            ownFill != null -> ownFill
            ph != null -> (fill(ph.onLayout?.child("p:spPr"), layout!!) ?: fill(ph.onMaster?.child("p:spPr"), master!!))?.takeIf { it !== NoFill }
            else -> style?.child("a:fillRef")?.takeIf { (it.attr("idx")?.toIntOrNull() ?: 0) > 0 }?.let { ref -> color(ref)?.let { SlideFill.Solid(it) } }
        }
        val line = line(spPr?.child("a:ln"), style)
        val text = node.child("p:txBody")?.let { text(it, ph, style) }?.takeIf { t -> t.paragraphs.any { p -> p.runs.isNotEmpty() } }
        if (fill == null && line == null && text == null) return null
        return SlideShape.Shape(
            frame, geometry, fill, line, text,
            flipH = xfrm?.attr("flipH") == "1", flipV = xfrm?.attr("flipV") == "1",
        )
    }

    private fun line(ln: XmlNode?, style: XmlNode?): SlideLine? {
        if (ln?.child("a:noFill") != null) return null
        val width = emu(ln?.attr("w")) ?: DefaultLineWidth
        ln?.child("a:solidFill")?.let { f -> return color(f)?.let { SlideLine(it, width) } }
        ln?.child("a:gradFill")?.let { g -> return g.child("a:gsLst")?.children("a:gs")?.firstOrNull()?.let(::color)?.let { SlideLine(it, width) } }
        val ref = style?.child("a:lnRef")?.takeIf { (it.attr("idx")?.toIntOrNull() ?: 0) > 0 } ?: return null
        return color(ref)?.let { SlideLine(it, width) }
    }

    private fun picture(node: XmlNode, part: Part, transform: Transform): SlideShape? {
        val ph = node.find("p:ph")?.let { Placeholder(it) }
        val xfrm = node.at("p:spPr", "a:xfrm") ?: ph?.onLayout?.at("p:spPr", "a:xfrm") ?: ph?.onMaster?.at("p:spPr", "a:xfrm")
        val frame = transform.apply(frame(xfrm) ?: return null)
        val blipFill = node.child("p:blipFill") ?: return null
        val rel = part.rels[blipFill.child("a:blip")?.attr("r:embed")] ?: return null
        if (rel.external) return null
        val src = blipFill.child("a:srcRect")
        fun crop(n: String) = (src?.attr(n)?.toIntOrNull() ?: 0) / 100_000f
        return SlideShape.Picture(
            frame, rel.target, crop("l"), crop("t"), crop("r"), crop("b"),
            node.find("p:cNvPr")?.attr("descr")?.takeIf { it.isNotBlank() },
        )
    }

    private fun table(node: XmlNode, part: Part, transform: Transform): SlideShape? {
        val tbl = node.find("a:tbl") ?: return null
        val frame = transform.apply(frame(node.child("p:xfrm")) ?: return null)
        val widths = tbl.child("a:tblGrid")?.children("a:gridCol")?.map { emu(it.attr("w")) ?: 0f }.orEmpty()
        val rows = tbl.children("a:tr")
        val heights = rows.map { emu(it.attr("h")) ?: 0f }
        val props = tbl.child("a:tblPr")
        val firstRow = props?.attr("firstRow") == "1"
        val banded = props?.attr("bandRow") == "1"
        // Without reading table styles, approximate PowerPoint's default (Medium Style 2): an
        // accent header row with white text, and lightly tinted bands below it.
        val accent = themeColors[colorMap["accent1"] ?: "accent1"] ?: themeColors["accent1"]
        val cells = rows.mapIndexed { r, row ->
            row.children("a:tc").map { tc ->
                val covered = tc.attr("hMerge") == "1" || tc.attr("vMerge") == "1"
                val tcPr = tc.child("a:tcPr")
                val ownFill = fill(tcPr, part)
                val isHeader = firstRow && r == 0
                val fill = when {
                    ownFill === NoFill -> null
                    ownFill is SlideFill.Solid -> ownFill.color
                    accent != null && isHeader -> accent
                    accent != null && banded && r % 2 == (if (firstRow) 1 else 0) -> ColorUtils.blendARGB(0xFFFFFFFF.toInt(), accent, 0.2f)
                    accent != null && banded -> ColorUtils.blendARGB(0xFFFFFFFF.toInt(), accent, 0.1f)
                    else -> null
                }
                val text = tc.child("a:txBody")?.let { body ->
                    text(body, null, null, defaultColor = if (isHeader && accent != null && ownFill == null) 0xFFFFFFFF.toInt() else null, forceBold = isHeader)
                } ?: SlideText(emptyList())
                SlideTableCell(
                    text.copy(
                        insetLeft = emu(tcPr?.attr("marL")) ?: 7.2f, insetRight = emu(tcPr?.attr("marR")) ?: 7.2f,
                        insetTop = emu(tcPr?.attr("marT")) ?: 3.6f, insetBottom = emu(tcPr?.attr("marB")) ?: 3.6f,
                        anchor = anchor(tcPr?.attr("anchor")) ?: SlideTextAnchor.Top,
                    ),
                    fill,
                    colSpan = tc.attr("gridSpan")?.toIntOrNull() ?: 1,
                    rowSpan = tc.attr("rowSpan")?.toIntOrNull() ?: 1,
                    covered = covered,
                )
            }
        }
        return SlideShape.Table(frame, widths, heights, cells)
    }

    // ---- text ----

    private fun anchor(value: String?): SlideTextAnchor? = when (value) {
        "t" -> SlideTextAnchor.Top
        "ctr" -> SlideTextAnchor.Middle
        "b" -> SlideTextAnchor.Bottom
        else -> null
    }

    /** The level properties (`a:lvl{n}pPr`) that apply to a paragraph at [level], nearest first:
     * the shape's own list style, the layout's and master's placeholder, the master's text style,
     * and the presentation's defaults. */
    private fun levelChain(body: XmlNode, ph: Placeholder?, level: Int): List<XmlNode> {
        val name = "a:lvl${level + 1}pPr"
        val styleName = ph?.textStyle ?: "p:otherStyle"
        return listOfNotNull(
            body.at("a:lstStyle", name),
            ph?.onLayout?.at("p:txBody", "a:lstStyle", name),
            ph?.onMaster?.at("p:txBody", "a:lstStyle", name),
            master?.xml?.at("p:txStyles", styleName, name),
            defaultText?.child(name),
        )
    }

    private fun text(body: XmlNode, ph: Placeholder?, style: XmlNode?, defaultColor: Int? = null, forceBold: Boolean = false): SlideText {
        val bodyPr = body.child("a:bodyPr")
        val inheritedBodyPr = listOfNotNull(bodyPr, ph?.onLayout?.at("p:txBody", "a:bodyPr"), ph?.onMaster?.at("p:txBody", "a:bodyPr"))
        fun bodyAttr(n: String) = inheritedBodyPr.firstNotNullOfOrNull { it.attr(n) }
        val autofit = bodyPr?.child("a:normAutofit")
        val fontScale = (autofit?.attr("fontScale")?.toIntOrNull() ?: 100_000) / 100_000f
        val spacingCut = (autofit?.attr("lnSpcReduction")?.toIntOrNull() ?: 0) / 100_000f
        // A shape's own style may name its text color (white text on an accent shape).
        val styleColor = style?.child("a:fontRef")?.let { color(it) }
        val counters = IntArray(9)
        val paragraphs = body.children("a:p").map { p ->
            val pPr = p.child("a:pPr")
            val level = (pPr?.attr("lvl")?.toIntOrNull() ?: 0).coerceIn(0, 8)
            val chain = listOfNotNull(pPr) + levelChain(body, ph, level)
            fun pAttr(n: String) = chain.firstNotNullOfOrNull { it.attr(n) }
            fun pChild(vararg names: String): XmlNode? {
                for (c in chain) for (n in names) c.child(n)?.let { return it }
                return null
            }
            val runDefaults = chain.mapNotNull { it.child("a:defRPr") }
            val sizePt = runDefaults.firstNotNullOfOrNull { it.attr("sz")?.toIntOrNull() }?.div(100f) ?: DefaultSizePt
            val runs = ArrayList<TextRun>()
            for (c in p.children) {
                when (c.name) {
                    "a:r" -> runs.add(TextRun(c.child("a:t")?.allText().orEmpty(), runStyle(c.child("a:rPr"), runDefaults, styleColor, defaultColor, forceBold, sizePt)))
                    "a:br" -> runs.add(TextRun("\n", runStyle(c.child("a:rPr"), runDefaults, styleColor, defaultColor, forceBold, sizePt)))
                    "a:fld" -> {
                        val text = if (c.attr("type") == "slidenum") SlideNumberMark else c.child("a:t")?.allText().orEmpty()
                        runs.add(TextRun(text, runStyle(c.child("a:rPr"), runDefaults, styleColor, defaultColor, forceBold, sizePt)))
                    }
                }
            }
            // Numbering restarts when a paragraph without it intervenes at this level.
            val autoNum = pChild("a:buAutoNum", "a:buChar", "a:buNone")
            val bullet = when {
                runs.isEmpty() -> null
                autoNum?.name == "a:buChar" -> autoNum.attr("char")?.let { bulletLabel(it, level) }
                autoNum?.name == "a:buAutoNum" -> {
                    val start = autoNum.attr("startAt")?.toIntOrNull() ?: 1
                    counters[level] = if (counters[level] == 0) start else counters[level] + 1
                    autoNumber(counters[level], autoNum.attr("type").orEmpty())
                }
                else -> null
            }
            if (autoNum?.name != "a:buAutoNum") counters[level] = 0
            for (deeper in level + 1 until 9) counters[deeper] = 0
            val spaceBefore = pChild("a:spcBef")?.let { s ->
                s.child("a:spcPts")?.attr("val")?.toIntOrNull()?.div(100f)
                    ?: s.child("a:spcPct")?.attr("val")?.toIntOrNull()?.let { it / 100_000f * sizePt }
            } ?: 0f
            val lineSpacing = pChild("a:lnSpc")?.child("a:spcPct")?.attr("val")?.toIntOrNull()?.div(100_000f) ?: 1f
            SlideParagraph(
                runs = mergeRuns(runs.filter { it.text.isNotEmpty() }),
                align = when (pAttr("algn")) {
                    "ctr" -> ParagraphAlign.Center
                    "r" -> ParagraphAlign.End
                    "just", "dist" -> ParagraphAlign.Justify
                    else -> ParagraphAlign.Start
                },
                bullet = bullet,
                level = level,
                spaceBeforePt = spaceBefore * fontScale,
                lineSpacing = (lineSpacing - spacingCut).coerceAtLeast(0.5f),
                emptySizePt = (p.child("a:endParaRPr")?.attr("sz")?.toIntOrNull()?.div(100f) ?: sizePt) * fontScale,
                marginLeftPt = emu(pAttr("marL")) ?: 0f,
                indentPt = emu(pAttr("indent")) ?: 0f,
            )
        }
        return SlideText(
            paragraphs = paragraphs,
            anchor = anchor(bodyAttr("anchor")) ?: SlideTextAnchor.Top,
            insetLeft = emu(bodyAttr("lIns")) ?: 7.2f,
            insetTop = emu(bodyAttr("tIns")) ?: 3.6f,
            insetRight = emu(bodyAttr("rIns")) ?: 7.2f,
            insetBottom = emu(bodyAttr("bIns")) ?: 3.6f,
            fontScale = fontScale,
            wrap = bodyAttr("wrap") != "none",
        )
    }

    private fun runStyle(
        rPr: XmlNode?,
        defaults: List<XmlNode>,
        styleColor: Int?,
        defaultColor: Int?,
        forceBold: Boolean,
        paragraphSize: Float,
    ): RunStyle {
        val chain = listOfNotNull(rPr) + defaults
        fun attr(n: String) = chain.firstNotNullOfOrNull { it.attr(n) }
        val ownColor = rPr?.child("a:solidFill")?.let { color(it) }
        val inheritedColor = defaults.firstNotNullOfOrNull { d -> d.child("a:solidFill")?.let { color(it) } }
        val link = rPr?.child("a:hlinkClick")?.attr("r:id")?.let { slide.rels[it] }?.takeIf { it.external }?.target
        val baseline = attr("baseline")?.toIntOrNull() ?: 0
        val typeface = chain.firstNotNullOfOrNull { it.child("a:latin")?.attr("typeface") }
        return RunStyle(
            bold = forceBold || attr("b") == "1",
            italic = attr("i") == "1",
            underline = attr("u").let { it != null && it != "none" } || link != null,
            strike = attr("strike").let { it != null && it != "noStrike" },
            // A link without its own color takes the theme's link color, as PowerPoint draws it.
            color = ownColor ?: defaultColor ?: link?.let { themeColors[colorMap["hlink"] ?: "hlink"] ?: themeColors["hlink"] }
                ?: styleColor ?: inheritedColor,
            highlight = rPr?.child("a:highlight")?.let { color(it) },
            sizePt = attr("sz")?.toIntOrNull()?.div(100f) ?: paragraphSize,
            position = when {
                baseline > 0 -> TextPosition.Superscript
                baseline < 0 -> TextPosition.Subscript
                else -> TextPosition.Normal
            },
            monospace = typeface?.let { t -> listOf("Courier", "Consolas", "Mono").any { t.contains(it, ignoreCase = true) } } ?: false,
            link = link,
        )
    }

    private companion object {
        const val DefaultSizePt = 18f
        const val DefaultLineWidth = 0.75f
        val ColorElements = setOf("a:srgbClr", "a:schemeClr", "a:sysClr", "a:prstClr", "a:scrgbClr", "a:hslClr")
    }
}

/** Marks a fill explicitly turned off, so it isn't replaced by an inherited one. */
private val NoFill = SlideFill.Solid(0)

/** DrawingML's auto-numbering schemes ("arabicPeriod", "alphaLcParenR", "romanUcPeriod"...). */
private fun autoNumber(n: Int, scheme: String): String {
    val format = when {
        scheme.startsWith("alphaLc") -> "lowerLetter"
        scheme.startsWith("alphaUc") -> "upperLetter"
        scheme.startsWith("romanLc") -> "lowerRoman"
        scheme.startsWith("romanUc") -> "upperRoman"
        else -> "decimal"
    }
    val number = listNumber(n, format)
    return when {
        scheme.endsWith("ParenBoth") -> "($number)"
        scheme.endsWith("ParenR") -> "$number)"
        scheme.endsWith("Plain") -> number
        scheme.endsWith("Minus") -> "- $number -"
        else -> "$number."
    }
}

private val PresetColors: Map<String?, Int> = mapOf(
    "black" to 0xFF000000.toInt(), "white" to 0xFFFFFFFF.toInt(), "red" to 0xFFFF0000.toInt(),
    "green" to 0xFF008000.toInt(), "blue" to 0xFF0000FF.toInt(), "yellow" to 0xFFFFFF00.toInt(),
    "gray" to 0xFF808080.toInt(), "orange" to 0xFFFFA500.toInt(), "purple" to 0xFF800080.toInt(),
)
