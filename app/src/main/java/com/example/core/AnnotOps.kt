package com.example.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLine
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquareCircle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class NPoint(val x: Float, val y: Float)

enum class MarkupKind { HIGHLIGHT, UNDERLINE, STRIKEOUT, SQUIGGLY }
enum class ShapeKind { RECT, OVAL, LINE, ARROW }
enum class ImageKind { IMAGE, SIGNATURE, STAMP }

/** An edit placed in the editor. Coordinates are normalised display coordinates of the page. */
sealed class Mark {
    abstract val page: Int
    abstract val color: Int
    abstract fun bounds(): NRect

    data class Ink(
        override val page: Int, val strokes: List<List<NPoint>>, override val color: Int,
        val width: Float, val opacity: Float = 1f
    ) : Mark() {
        override fun bounds(): NRect {
            val pts = strokes.flatten()
            return NRect(pts.minOf { it.x } - width, pts.minOf { it.y } - width, pts.maxOf { it.x } + width, pts.maxOf { it.y } + width)
        }
    }

    data class Shape(
        override val page: Int, val kind: ShapeKind, val start: NPoint, val end: NPoint,
        override val color: Int, val width: Float, val fill: Int? = null
    ) : Mark() {
        override fun bounds() = NRect(minOf(start.x, end.x) - width, minOf(start.y, end.y) - width, maxOf(start.x, end.x) + width, maxOf(start.y, end.y) + width)
    }

    /**
     * [size] is the font size as a fraction of the page's display width; [aspect] is the page's
     * display width / height, needed to convert that size into vertical (height-relative) units.
     */
    data class Text(
        override val page: Int, val at: NPoint, val text: String, override val color: Int,
        val size: Float, val bold: Boolean = false, val background: Int? = null, val aspect: Float = 0.707f
    ) : Mark() {
        /** Font size as a fraction of page height. */
        val sizeY: Float get() = size * aspect

        override fun bounds(): NRect {
            val lines = text.lines()
            val w = (lines.maxOfOrNull { it.length } ?: 1) * size * 0.55f
            return NRect(at.x, at.y, at.x + w, at.y + lines.size * sizeY * 1.25f)
        }
    }

    data class Note(override val page: Int, val at: NPoint, val text: String, override val color: Int) : Mark() {
        override fun bounds() = NRect(at.x, at.y, at.x + 0.04f, at.y + 0.03f)
    }

    data class Markup(override val page: Int, val kind: MarkupKind, val rects: List<NRect>, override val color: Int) : Mark() {
        override fun bounds() = rects.reduce { a, b -> a.union(b) }
    }

    data class Image(
        override val page: Int, val rect: NRect, val bitmap: Bitmap, val kind: ImageKind, val label: String = ""
    ) : Mark() {
        override val color: Int get() = 0
        override fun bounds() = rect
    }
}

data class ExistingAnnot(val index: Int, val subtype: String, val rect: NRect, val contents: String)

object AnnotOps {

    const val SIGNATURE_TAG = "PDFStudio-Signature"
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun pdColor(argb: Int) = RGB.of(argb).let { PDColor(floatArrayOf(it.r, it.g, it.b), PDDeviceRGB.INSTANCE) }

    fun listAnnotations(file: File, page: Int): List<ExistingAnnot> = runCatching {
        Workspace.load(file).use { doc ->
            val p = doc.getPage(page)
            val g = PageGeometry(p)
            p.annotations.mapIndexedNotNull { i, a ->
                if (a.subtype == "Popup" || a.subtype == "Widget" || a.subtype == "Link") return@mapIndexedNotNull null
                val r = a.rectangle ?: return@mapIndexedNotNull null
                val n = g.rectFromPdf(r.lowerLeftX, r.lowerLeftY, r.upperRightX, r.upperRightY)
                ExistingAnnot(i, a.subtype ?: "Annotation", NRect(n[0], n[1], n[2], n[3]), a.contents.orEmpty())
            }
        }
    }.getOrDefault(emptyList())

    suspend fun apply(
        context: Context, pdf: PickedPdf, marks: List<Mark>, removeExisting: Map<Int, Set<Int>>,
        author: String, flatten: Boolean, progress: Progress = NoProgress
    ): File = io {
        Workspace.loadForEdit(pdf).use { doc ->
            // Delete annotations first so indices refer to the original list.
            for ((pageIdx, indices) in removeExisting) {
                val page = doc.getPage(pageIdx)
                val list = page.annotations
                val keep = list.filterIndexed { i, a ->
                    val drop = i in indices
                    if (drop) runCatching { (a as? PDAnnotationMarkup)?.popup?.let { pop -> list.remove(pop) } }
                    !drop
                }
                page.annotations = keep.toMutableList()
            }
            marks.forEachIndexed { i, m ->
                progress(i / marks.size.coerceAtLeast(1).toFloat(), "Applying edits")
                val page = doc.getPage(m.page)
                val annot = build(doc, page, m, author) ?: return@forEachIndexed
                page.annotations.add(annot)
            }
            if (flatten) flattenAnnotations(doc)
            val f = Workspace.newOutput(context, pdf.baseName, "edited")
            doc.save(f); f
        }
    }

    /** Burns annotation appearances into page content so they can't be moved or removed. */
    fun flattenAnnotations(doc: PDDocument) {
        for (page in doc.pages) {
            val keep = mutableListOf<PDAnnotation>()
            for (a in page.annotations) {
                val ap = a.normalAppearanceStream
                if (ap == null || a.subtype == "Link" || a.subtype == "Widget" || a.subtype == "Popup" || a.isHidden) { if (a.subtype != "Popup") keep += a; continue }
                val rect = a.rectangle ?: continue
                val bbox = ap.bBox ?: continue
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.saveGraphicsState()
                    val m = Matrix.getTranslateInstance(rect.lowerLeftX, rect.lowerLeftY)
                    m.concatenate(Matrix.getScaleInstance(rect.width / bbox.width.coerceAtLeast(0.001f), rect.height / bbox.height.coerceAtLeast(0.001f)))
                    m.concatenate(Matrix.getTranslateInstance(-bbox.lowerLeftX, -bbox.lowerLeftY))
                    cs.transform(m)
                    cs.drawForm(ap)
                    cs.restoreGraphicsState()
                }
            }
            page.annotations = keep
        }
    }

    private fun pdfBounds(g: PageGeometry, r: NRect, pad: Float = 2f): PDRectangle {
        val a = g.rectToPdf(r.left, r.top, r.right, r.bottom)
        return PDRectangle(a[0] - pad, a[1] - pad, a[2] + 2 * pad, a[3] + 2 * pad)
    }

    /** Creates an appearance stream whose coordinates are PDF user space (BBox = Rect). */
    private fun appearance(doc: PDDocument, annot: PDAnnotation, rect: PDRectangle, draw: (PDPageContentStream) -> Unit) {
        val ap = PDAppearanceStream(doc)
        ap.bBox = rect
        ap.resources = PDResources()
        PDPageContentStream(doc, ap).use(draw)
        annot.appearance = PDAppearanceDictionary().apply { setNormalAppearance(ap) }
    }

    private fun alphaState(stroke: Float, fill: Float = stroke, blend: BlendMode? = null) = PDExtendedGraphicsState().apply {
        strokingAlphaConstant = stroke; nonStrokingAlphaConstant = fill
        if (blend != null) blendMode = blend
    }

    private fun build(doc: PDDocument, page: PDPage, m: Mark, author: String): PDAnnotation? {
        val g = PageGeometry(page)
        fun pt(p: NPoint) = g.toPdf(p.x, p.y)
        val color = RGB.of(m.color)
        return when (m) {
            is Mark.Ink -> {
                val a = PDAnnotationMarkup()
                a.cosObject.setName(COSName.SUBTYPE, PDAnnotationMarkup.SUB_TYPE_INK)
                val rect = pdfBounds(g, m.bounds())
                a.rectangle = rect
                a.inkList = m.strokes.map { s -> s.flatMap { p -> pt(p).toList() }.toFloatArray() }.toTypedArray()
                val lw = g.scale(m.width)
                a.borderStyle = PDBorderStyleDictionary().apply { width = lw }
                a.constantOpacity = m.opacity
                appearance(doc, a, rect) { cs ->
                    if (m.opacity < 1f) cs.setGraphicsStateParameters(alphaState(m.opacity, m.opacity, if (m.opacity < 0.6f) BlendMode.MULTIPLY else null))
                    cs.setStrokingColor(color.r, color.g, color.b)
                    cs.setLineWidth(lw); cs.setLineCapStyle(1); cs.setLineJoinStyle(1)
                    for (s in m.strokes) {
                        if (s.isEmpty()) continue
                        val (x0, y0) = pt(s[0]); cs.moveTo(x0, y0)
                        if (s.size == 1) cs.lineTo(x0 + 0.1f, y0)
                        for (p in s.drop(1)) { val (x, y) = pt(p); cs.lineTo(x, y) }
                        cs.stroke()
                    }
                }
                a
            }
            is Mark.Shape -> {
                val rect = pdfBounds(g, m.bounds())
                val lw = g.scale(m.width)
                val a: PDAnnotationMarkup = when (m.kind) {
                    ShapeKind.RECT -> PDAnnotationSquareCircle(PDAnnotationSquareCircle.SUB_TYPE_SQUARE)
                    ShapeKind.OVAL -> PDAnnotationSquareCircle(PDAnnotationSquareCircle.SUB_TYPE_CIRCLE)
                    else -> PDAnnotationLine().apply {
                        val (x1, y1) = pt(m.start); val (x2, y2) = pt(m.end)
                        line = floatArrayOf(x1, y1, x2, y2)
                        if (m.kind == ShapeKind.ARROW) endPointEndingStyle = PDAnnotationLine.LE_OPEN_ARROW
                    }
                }
                a.rectangle = rect
                a.borderStyle = PDBorderStyleDictionary().apply { width = lw }
                appearance(doc, a, rect) { cs ->
                    cs.setStrokingColor(color.r, color.g, color.b)
                    cs.setLineWidth(lw); cs.setLineCapStyle(1); cs.setLineJoinStyle(1)
                    val l = minOf(m.start.x, m.end.x); val r = maxOf(m.start.x, m.end.x)
                    val t = minOf(m.start.y, m.end.y); val b = maxOf(m.start.y, m.end.y)
                    when (m.kind) {
                        ShapeKind.RECT -> {
                            val pts = listOf(NPoint(l, t), NPoint(r, t), NPoint(r, b), NPoint(l, b)).map(::pt)
                            cs.moveTo(pts[0].first, pts[0].second); pts.drop(1).forEach { cs.lineTo(it.first, it.second) }; cs.closePath()
                            fillOrStroke(cs, m.fill)
                        }
                        ShapeKind.OVAL -> {
                            val pts = (0..48).map { k ->
                                val ang = 2 * Math.PI * k / 48
                                pt(NPoint(((l + r) / 2 + (r - l) / 2 * cos(ang)).toFloat(), ((t + b) / 2 + (b - t) / 2 * sin(ang)).toFloat()))
                            }
                            cs.moveTo(pts[0].first, pts[0].second); pts.drop(1).forEach { cs.lineTo(it.first, it.second) }; cs.closePath()
                            fillOrStroke(cs, m.fill)
                        }
                        ShapeKind.LINE, ShapeKind.ARROW -> {
                            val (x1, y1) = pt(m.start); val (x2, y2) = pt(m.end)
                            cs.moveTo(x1, y1); cs.lineTo(x2, y2); cs.stroke()
                            if (m.kind == ShapeKind.ARROW) {
                                val ang = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
                                val len = maxOf(lw * 4f, hypot(x2 - x1, y2 - y1) * 0.08f).coerceAtMost(40f)
                                for (d in doubleArrayOf(-0.45, 0.45)) {
                                    cs.moveTo(x2, y2)
                                    cs.lineTo((x2 - len * cos(ang + d)).toFloat(), (y2 - len * sin(ang + d)).toFloat())
                                    cs.stroke()
                                }
                            }
                        }
                    }
                }
                a.color = pdColor(m.color)
                if (m.fill != null && a is PDAnnotationSquareCircle) a.interiorColor = pdColor(m.fill)
                a
            }
            is Mark.Text -> {
                val a = PDAnnotationMarkup()
                a.cosObject.setName(COSName.SUBTYPE, "FreeText")
                val rect = pdfBounds(g, m.bounds(), 4f)
                a.rectangle = rect
                a.contents = m.text
                val size = g.scale(m.size)
                a.defaultAppearance = "/Helv ${"%.1f".format(java.util.Locale.US, size)} Tf ${color.r} ${color.g} ${color.b} rg"
                a.borderStyle = PDBorderStyleDictionary().apply { width = 0f }
                appearance(doc, a, rect) { cs ->
                    if (m.background != null) {
                        val bg = RGB.of(m.background)
                        cs.setNonStrokingColor(bg.r, bg.g, bg.b)
                        val c = listOf(NPoint(m.bounds().left, m.bounds().top), NPoint(m.bounds().right, m.bounds().top),
                            NPoint(m.bounds().right, m.bounds().bottom), NPoint(m.bounds().left, m.bounds().bottom)).map(::pt)
                        cs.moveTo(c[0].first, c[0].second); c.drop(1).forEach { cs.lineTo(it.first, it.second) }; cs.closePath(); cs.fill()
                    }
                    val font = PdfFonts.forText(doc, m.text, m.bold)
                    val lineH = m.sizeY * 1.25f
                    m.text.lines().forEachIndexed { li, line ->
                        val safe = PdfFonts.safe(font, line)
                        if (safe.isEmpty()) return@forEachIndexed
                        val (px, py) = pt(NPoint(m.at.x, m.at.y + lineH * li + m.sizeY * 0.95f))
                        cs.beginText()
                        cs.setFont(font, size)
                        cs.setNonStrokingColor(color.r, color.g, color.b)
                        cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(g.rotation.toDouble()), px, py))
                        cs.showText(safe)
                        cs.endText()
                    }
                }
                a
            }
            is Mark.Note -> {
                val a = PDAnnotationText()
                val (x, y) = pt(m.at)
                a.rectangle = PDRectangle(x, y - 20f, 20f, 20f)
                a.contents = m.text
                a.name = PDAnnotationText.NAME_COMMENT
                a.color = pdColor(m.color)
                appearance(doc, a, a.rectangle) { cs ->
                    val r = a.rectangle
                    cs.setNonStrokingColor(color.r, color.g, color.b)
                    cs.setStrokingColor(color.r * 0.6f, color.g * 0.6f, color.b * 0.6f)
                    cs.addRect(r.lowerLeftX + 1, r.lowerLeftY + 4, 18f, 15f); cs.fillAndStroke()
                    cs.moveTo(r.lowerLeftX + 5, r.lowerLeftY + 4); cs.lineTo(r.lowerLeftX + 4, r.lowerLeftY); cs.lineTo(r.lowerLeftX + 10, r.lowerLeftY + 4); cs.fill()
                    cs.setStrokingColor(1f, 1f, 1f); cs.setLineWidth(1.2f)
                    for (k in 0..2) { val yy = r.lowerLeftY + 15 - k * 4f; cs.moveTo(r.lowerLeftX + 4, yy); cs.lineTo(r.lowerLeftX + 16, yy); cs.stroke() }
                }
                a
            }
            is Mark.Markup -> {
                val sub = when (m.kind) {
                    MarkupKind.HIGHLIGHT -> PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT
                    MarkupKind.UNDERLINE -> PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE
                    MarkupKind.STRIKEOUT -> PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT
                    MarkupKind.SQUIGGLY -> PDAnnotationTextMarkup.SUB_TYPE_SQUIGGLY
                }
                val a = PDAnnotationTextMarkup(sub)
                val rect = pdfBounds(g, m.bounds(), 1f)
                a.rectangle = rect
                a.color = pdColor(m.color)
                a.quadPoints = m.rects.flatMap { r ->
                    listOf(NPoint(r.left, r.top), NPoint(r.right, r.top), NPoint(r.left, r.bottom), NPoint(r.right, r.bottom)).flatMap { pt(it).toList() }
                }.toFloatArray()
                if (m.kind == MarkupKind.HIGHLIGHT) a.constantOpacity = 0.45f
                appearance(doc, a, rect) { cs ->
                    for (r in m.rects) {
                        val tl = pt(NPoint(r.left, r.top)); val tr = pt(NPoint(r.right, r.top))
                        val bl = pt(NPoint(r.left, r.bottom)); val br = pt(NPoint(r.right, r.bottom))
                        val thick = (g.scale(r.height) * 0.07f).coerceIn(0.6f, 3f)
                        when (m.kind) {
                            MarkupKind.HIGHLIGHT -> {
                                cs.setGraphicsStateParameters(alphaState(0.45f, 0.45f, BlendMode.MULTIPLY))
                                cs.setNonStrokingColor(color.r, color.g, color.b)
                                cs.moveTo(tl.first, tl.second); cs.lineTo(tr.first, tr.second); cs.lineTo(br.first, br.second); cs.lineTo(bl.first, bl.second)
                                cs.closePath(); cs.fill()
                            }
                            MarkupKind.UNDERLINE, MarkupKind.STRIKEOUT -> {
                                val f = if (m.kind == MarkupKind.UNDERLINE) 0.92f else 0.55f
                                val a1 = pt(NPoint(r.left, r.top + r.height * f)); val a2 = pt(NPoint(r.right, r.top + r.height * f))
                                cs.setStrokingColor(color.r, color.g, color.b); cs.setLineWidth(thick)
                                cs.moveTo(a1.first, a1.second); cs.lineTo(a2.first, a2.second); cs.stroke()
                            }
                            MarkupKind.SQUIGGLY -> {
                                cs.setStrokingColor(color.r, color.g, color.b); cs.setLineWidth(thick * 0.8f)
                                val steps = ((r.width / r.height) * 4).toInt().coerceIn(4, 400)
                                for (k in 0..steps) {
                                    val x = r.left + r.width * k / steps
                                    val y = r.top + r.height * (if (k % 2 == 0) 0.88f else 0.98f)
                                    val p = pt(NPoint(x, y))
                                    if (k == 0) cs.moveTo(p.first, p.second) else cs.lineTo(p.first, p.second)
                                }
                                cs.stroke()
                            }
                        }
                    }
                }
                a
            }
            is Mark.Image -> {
                val a = PDAnnotationRubberStamp()
                val rect = pdfBounds(g, m.rect, 0f)
                a.rectangle = rect
                if (m.kind == ImageKind.SIGNATURE) a.annotationName = "$SIGNATURE_TAG-${System.currentTimeMillis()}"
                a.contents = m.label.ifBlank { m.kind.name.lowercase().replaceFirstChar { it.uppercase() } }
                val img = LosslessFactory.createFromImage(doc, m.bitmap)
                appearance(doc, a, rect) { cs ->
                    // Map the unit square so the image appears upright whatever the page rotation.
                    val o = pt(NPoint(m.rect.left, m.rect.bottom))
                    val x = pt(NPoint(m.rect.right, m.rect.bottom))
                    val y = pt(NPoint(m.rect.left, m.rect.top))
                    cs.drawImage(img, Matrix(x.first - o.first, x.second - o.second, y.first - o.first, y.second - o.second, o.first, o.second))
                }
                a
            }
        }?.also { a ->
            a.isPrinted = true
            if (a is PDAnnotationMarkup) {
                a.titlePopup = author
                a.creationDate = Calendar.getInstance()
            }
            a.setModifiedDate(Calendar.getInstance())
            a.page = page
            if (a.color == null && m !is Mark.Image) a.color = pdColor(m.color)
        }
    }

    private fun fillOrStroke(cs: PDPageContentStream, fill: Int?) {
        if (fill != null) {
            val f = RGB.of(fill)
            cs.setGraphicsStateParameters(alphaState(1f, android.graphics.Color.alpha(fill) / 255f))
            cs.setNonStrokingColor(f.r, f.g, f.b)
            cs.fillAndStroke()
        } else cs.stroke()
    }

    // ---------------- Stamps ----------------

    fun stampBitmap(text: String, argb: Int, sub: String? = null): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb; textSize = 96f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.08f
        }
        val subPaint = Paint(paint).apply { textSize = 34f; typeface = Typeface.DEFAULT; letterSpacing = 0.02f }
        val tw = paint.measureText(text)
        val sw = sub?.let { subPaint.measureText(it) } ?: 0f
        val w = (maxOf(tw, sw) + 80).toInt()
        val h = if (sub != null) 190 else 150
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 9f; color = argb }
        c.drawRoundRect(RectF(6f, 6f, w - 6f, h - 6f), 26f, 26f, border)
        c.drawRoundRect(RectF(18f, 18f, w - 18f, h - 18f), 18f, 18f, Paint(border).apply { strokeWidth = 3f })
        c.drawText(text, (w - tw) / 2, if (sub != null) 112f else 110f, paint)
        if (sub != null) c.drawText(sub, (w - sw) / 2, 160f, subPaint)
        return bmp
    }

    // ---------------- Redaction ----------------

    /**
     * Permanently removes content under [areas]: affected pages are rebuilt as images with the
     * areas painted over, so no underlying text or vector data survives. Other pages are untouched.
     */
    suspend fun redact(
        context: Context, pdf: PickedPdf, areas: Map<Int, List<NRect>>, fillColor: Int, dpi: Int,
        keepSearchable: Boolean, stripMetadata: Boolean, progress: Progress = NoProgress
    ): ToolResult {
        val out = Workspace.newOutput(context, pdf.baseName, "redacted")
        val renderer = withContext(Dispatchers.IO) { PageRenderer(pdf.file) }
        var count = 0
        renderer.use { r ->
            withContext(Dispatchers.IO) { Workspace.loadForEdit(pdf) }.use { src ->
                PDDocument().use { doc ->
                    for (i in 0 until src.numberOfPages) {
                        val rects = areas[i].orEmpty()
                        if (rects.isEmpty()) { withContext(Dispatchers.IO) { PageOps.clonePage(doc, src.getPage(i)) }; continue }
                        progress(i / src.numberOfPages.toFloat(), "Redacting page ${i + 1}")
                        val bmp = withContext(Dispatchers.IO) { r.renderDpi(i, dpi) } ?: continue
                        val c = Canvas(bmp)
                        val paint = Paint().apply { color = fillColor or (0xFF shl 24); style = Paint.Style.FILL }
                        for (rc in rects) {
                            c.drawRect(rc.left * bmp.width, rc.top * bmp.height, rc.right * bmp.width, rc.bottom * bmp.height, paint)
                            count++
                        }
                        val text = if (keepSearchable) runCatching { OcrOps.recognize(bmp) }.getOrNull() else null
                        withContext(Dispatchers.IO) {
                            val (w, h) = r.pageSize(i)
                            val page = PDPage(PDRectangle(w.toFloat(), h.toFloat()))
                            doc.addPage(page)
                            val img = com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(doc, bmp, 0.9f)
                            PDPageContentStream(doc, page).use { it.drawImage(img, 0f, 0f, w.toFloat(), h.toFloat()) }
                            if (text != null) OcrOps.addTextLayer(doc, page, text, bmp.width, bmp.height)
                        }
                        bmp.recycle()
                    }
                    if (!stripMetadata) PageOps.copyInfo(src, doc)
                    else doc.documentInformation.producer = "PDF Studio"
                    withContext(Dispatchers.IO) { doc.save(out) }
                }
            }
        }
        return ToolResult(listOf(out), "$count area(s) permanently redacted",
            listOf("Areas removed" to count.toString(), "Pages affected" to areas.count { it.value.isNotEmpty() }.toString()))
    }
}
