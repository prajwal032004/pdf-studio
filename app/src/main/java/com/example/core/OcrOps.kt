package com.example.core

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

data class OcrOptions(
    val dpi: Int = 250,
    val autoRotate: Boolean = true,
    val deskew: Boolean = false,
    val removeBlank: Boolean = false,
    val skipPagesWithText: Boolean = true
)

data class PageScan(val page: Int, val orientation: Int, val skew: Float, val blank: Boolean, val chars: Int)

data class BarcodeHit(val page: Int, val format: String, val type: String, val value: String, val rect: NRect?)

object OcrOps {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun recognize(bmp: Bitmap): Text = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()

    private fun score(t: Text): Int =
        t.textBlocks.sumOf { b -> b.lines.sumOf { l -> Regex("[A-Za-z]{3,}|\\d{2,}").findAll(l.text).sumOf { it.value.length } } }

    /** Returns the clockwise rotation (0/90/180/270) that makes the page read upright. */
    suspend fun detectOrientation(bmp: Bitmap): Int {
        val small = Bitmaps.scaleToMax(bmp, 1100)
        var best = 0; var bestScore = -1
        for (deg in intArrayOf(0, 90, 180, 270)) {
            val s = score(recognize(Bitmaps.rotate(small, deg.toFloat())))
            if (s > bestScore * 1.15f) { bestScore = s; best = deg }
        }
        return if (bestScore < 12) 0 else best
    }

    /** Median text-line angle in degrees (positive = rotated clockwise). */
    fun skewAngle(t: Text): Float {
        val angles = t.textBlocks.flatMap { b -> b.lines.filter { it.text.length > 8 }.map { it.angle } }
            .filter { kotlin.math.abs(it) < 20f }.sorted()
        return if (angles.size < 3) 0f else angles[angles.size / 2]
    }

    suspend fun isBlank(bmp: Bitmap, sensitivity: Float = 0.004f): Boolean {
        if (Bitmaps.inkRatio(bmp) > sensitivity * 6) return false
        if (Bitmaps.inkRatio(bmp) < sensitivity) return true
        return score(recognize(Bitmaps.scaleToMax(bmp, 1000))) < 4
    }

    suspend fun analyse(pdf: PickedPdf, progress: Progress = NoProgress): List<PageScan> {
        val out = mutableListOf<PageScan>()
        val renderer = withContext(Dispatchers.IO) { PageRenderer(pdf.file) }
        renderer.use { r ->
            for (i in 0 until r.pageCount) {
                progress(i / r.pageCount.toFloat(), "Scanning page ${i + 1}")
                val bmp = withContext(Dispatchers.IO) { r.render(i, 1200) } ?: continue
                val blank = isBlank(bmp)
                if (blank) { out += PageScan(i, 0, 0f, true, 0); continue }
                val orient = detectOrientation(bmp)
                val t = recognize(Bitmaps.rotate(bmp, orient.toFloat()))
                out += PageScan(i, orient, skewAngle(t), false, t.text.length)
            }
        }
        return out
    }

    /** Fixes orientation losslessly with the page /Rotate flag. */
    suspend fun fixOrientation(context: Context, pdf: PickedPdf, scans: List<PageScan>): File {
        val rot = scans.filter { it.orientation != 0 }.associate { it.page to it.orientation }
        return PageOps.rebuild(context, pdf, (0 until pdf.pageCount).toList(), rot, "oriented")
    }

    suspend fun removeBlankPages(context: Context, pdf: PickedPdf, blanks: Set<Int>): File =
        PageOps.deletePages(context, pdf, blanks)

    /**
     * Produces a searchable PDF: pages keep their original look and get an invisible, selectable
     * text layer. Pages that need rotation/deskew are re-rendered as corrected images.
     */
    suspend fun makeSearchable(
        context: Context, pdf: PickedPdf, opt: OcrOptions, progress: Progress = NoProgress
    ): ToolResult {
        val textOut = StringBuilder()
        var pagesOcred = 0; var removed = 0; var corrected = 0
        val out = Workspace.newOutput(context, pdf.baseName, "ocr")
        val pagesWithText = if (opt.skipPagesWithText) withContext(Dispatchers.IO) { pagesHavingText(pdf.file) } else emptySet()
        val renderer = withContext(Dispatchers.IO) { PageRenderer(pdf.file) }
        renderer.use { r ->
            withContext(Dispatchers.IO) { Workspace.loadForEdit(pdf) }.use { src ->
                PDDocument().use { doc ->
                    for (i in 0 until r.pageCount) {
                        progress(i / r.pageCount.toFloat(), "Recognising page ${i + 1} of ${r.pageCount}")
                        var bmp = withContext(Dispatchers.IO) { r.renderDpi(i, opt.dpi) }
                        if (bmp == null) { PageOps.clonePage(doc, src.getPage(i)); continue }
                        if (opt.removeBlank && isBlank(bmp)) { removed++; continue }
                        if (i in pagesWithText) {
                            PageOps.clonePage(doc, src.getPage(i))
                            textOut.append("—— Page ${i + 1} ——\n").append(ConvertOps.extractText(pdf.file, listOf(i))).append("\n")
                            continue
                        }
                        var rotation = 0
                        if (opt.autoRotate) rotation = detectOrientation(bmp)
                        if (rotation != 0) bmp = Bitmaps.rotate(bmp, rotation.toFloat())
                        var rasterize = rotation != 0
                        var text = recognize(bmp)
                        if (opt.deskew) {
                            val skew = skewAngle(text)
                            if (kotlin.math.abs(skew) > 0.4f) {
                                bmp = deskewBitmap(bmp, skew)
                                text = recognize(bmp)
                                rasterize = true
                            }
                        }
                        pagesOcred++
                        textOut.append("—— Page ${i + 1} ——\n").append(text.text).append("\n\n")
                        val finalBmp = bmp
                        withContext(Dispatchers.IO) {
                            if (rasterize) {
                                corrected++
                                val (wPt, hPt) = r.pageSize(i).let { (w, h) -> if ((finalBmp.width > finalBmp.height) == (w > h)) w to h else h to w }
                                val page = PDPage(PDRectangle(wPt.toFloat(), hPt.toFloat()))
                                doc.addPage(page)
                                val img = JPEGFactory.createFromImage(doc, finalBmp, 0.85f)
                                PDPageContentStream(doc, page).use { it.drawImage(img, 0f, 0f, wPt.toFloat(), hPt.toFloat()) }
                                addTextLayer(doc, page, text, finalBmp.width, finalBmp.height)
                            } else {
                                val page = PageOps.clonePage(doc, src.getPage(i))
                                addTextLayer(doc, page, text, finalBmp.width, finalBmp.height)
                            }
                        }
                        finalBmp.recycle()
                    }
                    require(doc.numberOfPages > 0) { "Every page was blank" }
                    PageOps.copyInfo(src, doc)
                    withContext(Dispatchers.IO) { doc.save(out) }
                }
            }
        }
        val txt = File(out.parentFile, out.nameWithoutExtension + ".txt").apply { writeText(textOut.toString()) }
        return ToolResult(
            listOf(out, txt),
            "Searchable PDF ready — $pagesOcred page(s) recognised",
            listOfNotNull(
                "Pages recognised" to pagesOcred.toString(),
                if (corrected > 0) "Pages straightened" to corrected.toString() else null,
                if (removed > 0) "Blank pages removed" to removed.toString() else null,
                "Characters" to textOut.length.toString()
            )
        )
    }

    fun pagesHavingText(file: File): Set<Int> = runCatching {
        Workspace.load(file).use { doc ->
            val st = com.tom_roush.pdfbox.text.PDFTextStripper()
            (0 until doc.numberOfPages).filter { p ->
                st.startPage = p + 1; st.endPage = p + 1
                st.getText(doc).count { it.isLetterOrDigit() } > 20
            }.toSet()
        }
    }.getOrDefault(emptySet())

    fun deskewBitmap(bmp: Bitmap, angle: Float): Bitmap {
        val rotated = Bitmaps.rotate(bmp, -angle)
        // Fill the corners created by rotation with white.
        val out = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.drawColor(android.graphics.Color.WHITE)
        c.drawBitmap(rotated, 0f, 0f, null)
        val cx = (out.width - bmp.width) / 2
        val cy = (out.height - bmp.height) / 2
        return Bitmap.createBitmap(out, cx.coerceAtLeast(0), cy.coerceAtLeast(0), bmp.width.coerceAtMost(out.width), bmp.height.coerceAtMost(out.height))
    }

    /** Writes recognised lines as invisible text positioned over the page image. */
    fun addTextLayer(doc: PDDocument, page: PDPage, text: Text, bmpW: Int, bmpH: Int) {
        val g = PageGeometry(page)
        val all = text.text
        val font: PDFont = PdfFonts.forText(doc, all.take(4000))
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.beginText()
            cs.setRenderingMode(RenderingMode.NEITHER)
            for (block in text.textBlocks) for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val str = PdfFonts.safe(font, line.text)
                if (str.isBlank()) continue
                val hPt = box.height() * g.displayH / bmpH
                val wPt = box.width() * g.displayW / bmpW
                val size = (hPt * 0.85f).coerceIn(2f, 200f)
                val natural = PdfFonts.width(font, str, size)
                if (natural <= 0f) continue
                val u = box.left.toFloat() / bmpW
                val v = (box.bottom.toFloat() - box.height() * 0.18f) / bmpH
                val (px, py) = g.toPdf(u, v)
                cs.setFont(font, size)
                cs.setHorizontalScaling((wPt / natural * 100f).coerceIn(10f, 500f))
                cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(g.rotation.toDouble()), px, py))
                cs.showText(str)
            }
            cs.endText()
        }
    }

    /** OCR only: returns the recognised text of each page. */
    suspend fun extractScannedText(context: Context, pdf: PickedPdf, dpi: Int, autoRotate: Boolean, progress: Progress = NoProgress): ToolResult {
        val sb = StringBuilder()
        val renderer = withContext(Dispatchers.IO) { PageRenderer(pdf.file) }
        renderer.use { r ->
            for (i in 0 until r.pageCount) {
                progress(i / r.pageCount.toFloat(), "Reading page ${i + 1} of ${r.pageCount}")
                var bmp = withContext(Dispatchers.IO) { r.renderDpi(i, dpi) } ?: continue
                if (autoRotate) bmp = Bitmaps.rotate(bmp, detectOrientation(bmp).toFloat())
                sb.append("—— Page ${i + 1} ——\n").append(recognize(bmp).text).append("\n\n")
            }
        }
        val f = Workspace.newOutput(context, pdf.baseName, "ocr_text", "txt")
        withContext(Dispatchers.IO) { f.writeText(sb.toString()) }
        val words = sb.split(Regex("\\s+")).count { it.isNotBlank() }
        return ToolResult(listOf(f), "Recognised $words words", listOf("Words" to words.toString(), "Characters" to sb.length.toString()))
    }

    /** Re-renders every page with skew removed. */
    suspend fun deskewPdf(context: Context, pdf: PickedPdf, dpi: Int, progress: Progress = NoProgress): ToolResult {
        val out = Workspace.newOutput(context, pdf.baseName, "deskewed")
        var fixed = 0
        val renderer = withContext(Dispatchers.IO) { PageRenderer(pdf.file) }
        renderer.use { r ->
            PDDocument().use { doc ->
                for (i in 0 until r.pageCount) {
                    progress(i / r.pageCount.toFloat(), "Straightening page ${i + 1}")
                    var bmp = withContext(Dispatchers.IO) { r.renderDpi(i, dpi) } ?: continue
                    val skew = skewAngle(recognize(Bitmaps.scaleToMax(bmp, 1600)))
                    if (kotlin.math.abs(skew) > 0.3f) { bmp = deskewBitmap(bmp, skew); fixed++ }
                    val (w, h) = r.pageSize(i)
                    withContext(Dispatchers.IO) {
                        val page = PDPage(PDRectangle(w.toFloat(), h.toFloat()))
                        doc.addPage(page)
                        val img = JPEGFactory.createFromImage(doc, bmp, 0.85f)
                        PDPageContentStream(doc, page).use { it.drawImage(img, 0f, 0f, w.toFloat(), h.toFloat()) }
                    }
                }
                withContext(Dispatchers.IO) { doc.save(out) }
            }
        }
        return ToolResult(listOf(out), if (fixed == 0) "No skew detected — pages were already straight" else "Straightened $fixed page(s)")
    }

    // ---------------- Barcodes ----------------

    private val barcodeClient by lazy { BarcodeScanning.getClient() }

    suspend fun scanBarcodes(pdf: PickedPdf, progress: Progress = NoProgress): List<BarcodeHit> {
        val hits = mutableListOf<BarcodeHit>()
        val renderer = withContext(Dispatchers.IO) { PageRenderer(pdf.file) }
        renderer.use { r ->
            for (i in 0 until r.pageCount) {
                progress(i / r.pageCount.toFloat(), "Scanning page ${i + 1}")
                val bmp = withContext(Dispatchers.IO) { r.render(i, 1800) } ?: continue
                val codes = barcodeClient.process(InputImage.fromBitmap(bmp, 0)).await()
                for (c in codes) {
                    val b = c.boundingBox
                    hits += BarcodeHit(
                        i, formatName(c.format), typeName(c.valueType), c.rawValue ?: c.displayValue ?: "",
                        b?.let { NRect(it.left / bmp.width.toFloat(), it.top / bmp.height.toFloat(), it.right / bmp.width.toFloat(), it.bottom / bmp.height.toFloat()) }
                    )
                }
            }
        }
        return hits
    }

    private fun formatName(f: Int) = when (f) {
        Barcode.FORMAT_QR_CODE -> "QR Code"; Barcode.FORMAT_AZTEC -> "Aztec"; Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
        Barcode.FORMAT_PDF417 -> "PDF417"; Barcode.FORMAT_EAN_13 -> "EAN-13"; Barcode.FORMAT_EAN_8 -> "EAN-8"
        Barcode.FORMAT_UPC_A -> "UPC-A"; Barcode.FORMAT_UPC_E -> "UPC-E"; Barcode.FORMAT_CODE_128 -> "Code 128"
        Barcode.FORMAT_CODE_39 -> "Code 39"; Barcode.FORMAT_CODE_93 -> "Code 93"; Barcode.FORMAT_CODABAR -> "Codabar"
        Barcode.FORMAT_ITF -> "ITF"; else -> "Barcode"
    }

    private fun typeName(t: Int) = when (t) {
        Barcode.TYPE_URL -> "Link"; Barcode.TYPE_WIFI -> "Wi-Fi"; Barcode.TYPE_EMAIL -> "Email"; Barcode.TYPE_PHONE -> "Phone"
        Barcode.TYPE_SMS -> "SMS"; Barcode.TYPE_CONTACT_INFO -> "Contact"; Barcode.TYPE_GEO -> "Location"
        Barcode.TYPE_CALENDAR_EVENT -> "Event"; Barcode.TYPE_ISBN -> "ISBN"; Barcode.TYPE_PRODUCT -> "Product"
        else -> "Text"
    }
}
