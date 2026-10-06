package com.example.core

import android.content.Context
import com.example.R
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDOutputIntent
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class FontInfo(val name: String, val type: String, val embedded: Boolean)

data class DocInfo(
    val fileName: String,
    val fileSize: Long,
    val version: Float,
    val pages: Int,
    val pageSizes: List<String>,
    val encrypted: Boolean,
    val meta: OrganizeOps.Meta,
    val created: String,
    val modified: String,
    val fonts: List<FontInfo>,
    val images: Int,
    val annotations: Int,
    val formFields: Int,
    val bookmarks: Int,
    val signatures: Int,
    val attachments: Int,
    val hasJavaScript: Boolean,
    val tagged: Boolean,
    val pdfaClaim: String?
)

data class SignatureReport(
    val name: String,
    val signer: String,
    val issuer: String,
    val signedAt: String,
    val reason: String,
    val location: String,
    val subFilter: String,
    val integrityValid: Boolean,
    val coversWholeDocument: Boolean,
    val certValidNow: Boolean,
    val selfSigned: Boolean,
    val error: String?
)

data class SignatureScan(val signed: List<SignatureReport>, val emptyFields: List<String>, val visualSignatures: List<Int>)

data class Check(val label: String, val ok: Boolean, val detail: String)

object AnalysisOps {

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
    private val dateFmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
    private fun fmt(c: Calendar?) = c?.let { dateFmt.format(it.time) } ?: "—"

    // ---------------- Properties ----------------

    suspend fun info(pdf: PickedPdf): DocInfo = io {
        Workspace.load(pdf.file).use { doc ->
            val fonts = LinkedHashMap<String, FontInfo>()
            var images = 0; var annots = 0
            val seenImages = HashSet<Any>()
            fun walk(res: PDResources?, depth: Int) {
                if (res == null || depth > 5) return
                for (fn in res.fontNames) runCatching {
                    val f = res.getFont(fn) ?: return@runCatching
                    val name = f.name ?: fn.name
                    fonts.getOrPut(name) { FontInfo(name, f.subType ?: "Font", f.isEmbedded) }
                }
                for (xn in res.xObjectNames) runCatching {
                    when (val xo = res.getXObject(xn)) {
                        is PDImageXObject -> if (seenImages.add(xo.cosObject)) images++
                        is PDFormXObject -> walk(xo.resources, depth + 1)
                    }
                }
            }
            val sizes = LinkedHashMap<String, Int>()
            for (p in doc.pages) {
                walk(p.resources, 0)
                annots += runCatching { p.annotations.size }.getOrDefault(0)
                val g = PageGeometry(p)
                val label = "${(g.displayW / 72 * 25.4).toInt()} × ${(g.displayH / 72 * 25.4).toInt()} mm"
                sizes[label] = (sizes[label] ?: 0) + 1
            }
            val cat = doc.documentCatalog
            val names = cat.names
            val info = doc.documentInformation
            DocInfo(
                fileName = pdf.name,
                fileSize = pdf.size,
                version = doc.version,
                pages = doc.numberOfPages,
                pageSizes = sizes.map { (k, v) -> "$k  ×$v" },
                encrypted = pdf.wasEncrypted,
                meta = OrganizeOps.Meta(info.title.orEmpty(), info.author.orEmpty(), info.subject.orEmpty(), info.keywords.orEmpty(), info.creator.orEmpty(), info.producer.orEmpty()),
                created = fmt(info.creationDate),
                modified = fmt(info.modificationDate),
                fonts = fonts.values.toList(),
                images = images,
                annotations = annots,
                formFields = runCatching { cat.acroForm?.fieldTree?.count() ?: 0 }.getOrDefault(0),
                bookmarks = OrganizeOps.readBookmarks(doc).size,
                signatures = runCatching { doc.signatureDictionaries.size }.getOrDefault(0),
                attachments = runCatching { names?.embeddedFiles?.names?.size ?: 0 }.getOrDefault(0),
                hasJavaScript = runCatching { names?.javaScript != null || cat.cosObject.containsKey(COSName.getPDFName("OpenAction")) && cat.cosObject.toString().contains("JavaScript") }.getOrDefault(false),
                tagged = runCatching { cat.markInfo?.isMarked == true }.getOrDefault(false),
                pdfaClaim = pdfaClaim(doc)
            )
        }
    }

    private fun pdfaClaim(doc: PDDocument): String? = runCatching {
        val xml = doc.documentCatalog.metadata?.exportXMPMetadata()?.bufferedReader()?.readText() ?: return null
        val part = Regex("pdfaid:part(?:>|=\")(\\d)").find(xml)?.groupValues?.get(1) ?: return null
        val conf = Regex("pdfaid:conformance(?:>|=\")([ABUabu])").find(xml)?.groupValues?.get(1) ?: ""
        "PDF/A-$part${conf.lowercase()}"
    }.getOrNull()

    // ---------------- Blank / duplicate pages ----------------

    suspend fun blankPages(pdf: PickedPdf, sensitivity: Float, progress: Progress = NoProgress): Set<Int> = io {
        val withText = OcrOps.pagesHavingText(pdf.file)
        PageRenderer(pdf.file).use { r ->
            (0 until r.pageCount).filter { i ->
                progress(i / r.pageCount.toFloat(), "Checking page ${i + 1}")
                if (i in withText) return@filter false
                val bmp = r.render(i, 600) ?: return@filter false
                // Ignore a thin border where scanners leave shadows.
                val inner = android.graphics.Bitmap.createBitmap(bmp, bmp.width / 20, bmp.height / 20, bmp.width * 9 / 10, bmp.height * 9 / 10)
                Bitmaps.inkRatio(inner) < sensitivity
            }.toSet()
        }
    }

    /** Groups of visually (and textually) identical pages. */
    suspend fun duplicatePages(pdf: PickedPdf, progress: Progress = NoProgress): List<List<Int>> = io {
        val texts = Workspace.load(pdf.file).use { doc ->
            val st = PDFTextStripper()
            (1..doc.numberOfPages).map { p ->
                st.startPage = p; st.endPage = p
                val t = runCatching { st.getText(doc) }.getOrDefault("").replace(Regex("\\s+"), " ").trim()
                if (t.isEmpty()) "" else MessageDigest.getInstance("MD5").digest(t.toByteArray()).joinToString("") { "%02x".format(it) }
            }
        }
        val hashes = PageRenderer(pdf.file).use { r ->
            (0 until r.pageCount).map { i ->
                progress(i / r.pageCount.toFloat(), "Fingerprinting page ${i + 1}")
                r.render(i, 240)?.let { Bitmaps.dHash(it) } ?: 0L
            }
        }
        val used = BooleanArray(hashes.size)
        val groups = mutableListOf<List<Int>>()
        for (i in hashes.indices) {
            if (used[i]) continue
            val g = mutableListOf(i)
            for (j in i + 1 until hashes.size) {
                if (used[j]) continue
                val close = java.lang.Long.bitCount(hashes[i] xor hashes[j]) <= 2
                val sameText = texts[i] == texts[j]
                if (close && sameText) { g += j; used[j] = true }
            }
            if (g.size > 1) groups += g
        }
        groups
    }

    // ---------------- Signatures ----------------

    suspend fun signatures(pdf: PickedPdf): SignatureScan = io {
        val bytes = pdf.file.readBytes()
        Workspace.load(pdf.file).use { doc ->
            val reports = doc.signatureDictionaries.map { sig ->
                try {
                    val contents = sig.getContents(bytes)
                    val signed = sig.getSignedContent(bytes)
                    val br = sig.byteRange
                    val covers = br != null && br.size == 4 && br[2] + br[3] == bytes.size
                    val sub = sig.subFilter ?: ""
                    val cms = if (sub.equals("adbe.pkcs7.sha1", true)) CMSSignedData(contents)
                    else CMSSignedData(CMSProcessableByteArray(signed), contents)
                    val signer = cms.signerInfos.signers.first()
                    @Suppress("UNCHECKED_CAST")
                    val holder = cms.certificates.getMatches(signer.sid as org.bouncycastle.util.Selector<X509CertificateHolder>).first() as X509CertificateHolder
                    val cert = JcaX509CertificateConverter().setProvider(BouncyCastleProvider()).getCertificate(holder)
                    var ok = signer.verify(JcaSimpleSignerInfoVerifierBuilder().setProvider(BouncyCastleProvider()).build(cert))
                    if (ok && sub.equals("adbe.pkcs7.sha1", true)) {
                        val inner = cms.signedContent?.content as? ByteArray
                        ok = inner != null && inner.contentEquals(MessageDigest.getInstance("SHA-1").digest(signed))
                    }
                    val validNow = runCatching { cert.checkValidity(); true }.getOrDefault(false)
                    SignatureReport(
                        name = sig.name ?: cn(cert.subjectX500Principal.name),
                        signer = cn(cert.subjectX500Principal.name),
                        issuer = cn(cert.issuerX500Principal.name),
                        signedAt = fmt(sig.signDate),
                        reason = sig.reason ?: "—",
                        location = sig.location ?: "—",
                        subFilter = sub,
                        integrityValid = ok,
                        coversWholeDocument = covers,
                        certValidNow = validNow,
                        selfSigned = cert.subjectX500Principal == cert.issuerX500Principal,
                        error = null
                    )
                } catch (e: Exception) {
                    SignatureReport(sig.name ?: "Signature", "—", "—", fmt(sig.signDate), sig.reason ?: "—", sig.location ?: "—",
                        sig.subFilter ?: "", false, false, false, false, e.message ?: e.javaClass.simpleName)
                }
            }
            val empty = runCatching {
                doc.documentCatalog.acroForm?.fieldTree?.filterIsInstance<PDSignatureField>()?.filter { it.value == null }?.map { it.fullyQualifiedName }
            }.getOrNull().orEmpty()
            val visual = doc.pages.mapIndexedNotNull { i, p ->
                val hit = runCatching {
                    p.annotations.any { a ->
                        a.annotationName?.startsWith(AnnotOps.SIGNATURE_TAG) == true ||
                            (a.subtype == "Ink" && (a.contents ?: "").contains("sign", true))
                    }
                }.getOrDefault(false)
                if (hit) i else null
            }
            SignatureScan(reports, empty, visual)
        }
    }

    private fun cn(dn: String): String =
        Regex("CN=([^,]+)").find(dn)?.groupValues?.get(1) ?: dn.take(60)

    // ---------------- PDF/A ----------------

    suspend fun validatePdfA(pdf: PickedPdf): List<Check> = io {
        Workspace.load(pdf.file).use { doc ->
            val cat = doc.documentCatalog
            val checks = mutableListOf<Check>()
            val claim = pdfaClaim(doc)
            checks += Check("PDF/A identification in XMP metadata", claim != null, claim ?: "No pdfaid entry")
            checks += Check("Output intent (colour profile)", cat.outputIntents.isNotEmpty(), "${cat.outputIntents.size} present")
            checks += Check("Not encrypted", !pdf.wasEncrypted, if (pdf.wasEncrypted) "Encryption is forbidden in PDF/A" else "OK")
            val fonts = mutableListOf<String>()
            for (p in doc.pages) {
                val res = p.resources ?: continue
                for (fn in res.fontNames) runCatching { val f = res.getFont(fn); if (f != null && !f.isEmbedded) fonts += (f.name ?: fn.name) }
            }
            checks += Check("All fonts embedded", fonts.isEmpty(), if (fonts.isEmpty()) "OK" else fonts.distinct().take(6).joinToString())
            val js = runCatching { cat.names?.javaScript != null }.getOrDefault(false)
            checks += Check("No JavaScript", !js, if (js) "Document-level scripts found" else "OK")
            val files = runCatching { cat.names?.embeddedFiles != null }.getOrDefault(false)
            checks += Check("No embedded files", !files, if (files) "Attachments present (allowed only in PDF/A-3)" else "OK")
            var badAnnots = 0
            for (p in doc.pages) runCatching {
                for (a in p.annotations) if (a.subtype != "Popup" && a.subtype != "Link" && (!a.isPrinted || a.appearance == null)) badAnnots++
            }
            checks += Check("Annotations printable with appearances", badAnnots == 0, if (badAnnots == 0) "OK" else "$badAnnots annotation(s) need fixing")
            checks += Check("Document ID present", doc.document.trailer.containsKey(COSName.ID), "Added automatically when saving")
            checks
        }
    }

    /**
     * Converts toward PDF/A-2b: adds XMP identification and an sRGB output intent, removes
     * JavaScript and attachments, fixes annotation flags. With [rasterize] every page becomes an
     * image, which guarantees font compliance for files with non-embedded fonts.
     */
    suspend fun convertPdfA(context: Context, pdf: PickedPdf, rasterize: Boolean, progress: Progress = NoProgress): File = io {
        val source = if (rasterize) {
            val tmp = Workspace.temp(context, "pdf")
            ImageOps.rasterizeTo(pdf, tmp, 200, 85, false, progress)
            tmp
        } else pdf.file
        Workspace.load(source).use { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            val cat = doc.documentCatalog
            runCatching { cat.names?.cosObject?.removeItem(COSName.JAVA_SCRIPT) }
            runCatching { cat.names?.embeddedFiles = null }
            cat.cosObject.removeItem(COSName.getPDFName("OpenAction"))
            cat.cosObject.removeItem(COSName.AA)
            for (p in doc.pages) runCatching {
                p.cosObject.removeItem(COSName.AA)
                for (a in p.annotations) { a.isPrinted = true; a.isHidden = false; a.isInvisible = false; a.isNoView = false }
            }
            val info = doc.documentInformation
            val title = info.title ?: pdf.baseName
            info.title = title
            info.producer = "PDF Studio"
            if (info.creationDate == null) info.creationDate = Calendar.getInstance()
            info.modificationDate = Calendar.getInstance()
            val meta = PDMetadata(doc)
            meta.importXMPMetadata(xmp(title, info.author ?: "", info.creationDate, info.modificationDate).toByteArray(Charsets.UTF_8))
            cat.metadata = meta
            if (cat.outputIntents.isEmpty()) {
                context.resources.openRawResource(R.raw.srgb_icc).use { icc ->
                    val intent = PDOutputIntent(doc, icc)
                    intent.info = "sRGB IEC61966-2.1"
                    intent.outputCondition = "sRGB"
                    intent.outputConditionIdentifier = "sRGB IEC61966-2.1"
                    intent.registryName = "http://www.color.org"
                    cat.addOutputIntent(intent)
                }
            }
            if (doc.version < 1.7f) doc.version = 1.7f
            val f = Workspace.newOutput(context, pdf.baseName, "pdfa")
            doc.save(f)
            if (source != pdf.file) source.delete()
            f
        }
    }

    private fun xmp(title: String, author: String, created: Calendar?, modified: Calendar?): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val c = iso.format((created ?: Calendar.getInstance()).time)
        val m = iso.format((modified ?: Calendar.getInstance()).time)
        return """<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about="" xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/">
   <pdfaid:part>2</pdfaid:part>
   <pdfaid:conformance>B</pdfaid:conformance>
  </rdf:Description>
  <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">
   <dc:format>application/pdf</dc:format>
   <dc:title><rdf:Alt><rdf:li xml:lang="x-default">${esc(title)}</rdf:li></rdf:Alt></dc:title>
   ${if (author.isNotBlank()) "<dc:creator><rdf:Seq><rdf:li>${esc(author)}</rdf:li></rdf:Seq></dc:creator>" else ""}
  </rdf:Description>
  <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/">
   <xmp:CreateDate>$c</xmp:CreateDate>
   <xmp:ModifyDate>$m</xmp:ModifyDate>
   <xmp:CreatorTool>PDF Studio</xmp:CreatorTool>
  </rdf:Description>
  <rdf:Description rdf:about="" xmlns:pdf="http://ns.adobe.com/pdf/1.3/">
   <pdf:Producer>PDF Studio</pdf:Producer>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""
    }

    // ---------------- Repair ----------------

    /**
     * Tries a lenient parse and full rewrite (rebuilds the cross-reference table). If PDFBox can't
     * parse it, falls back to the platform renderer and rebuilds the pages as images.
     */
    suspend fun repair(context: Context, uri: android.net.Uri, name: String, progress: Progress = NoProgress): ToolResult {
        val raw = Workspace.copyToWork(context, uri)
        return io {
            val base = Workspace.baseName(name)
            val out = Workspace.newOutput(context, base, "repaired")
            val first = runCatching {
                progress(0.2f, "Rebuilding document structure")
                Workspace.load(raw).use { doc ->
                    if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
                    require(doc.numberOfPages > 0) { "No pages recovered" }
                    doc.save(out)
                    doc.numberOfPages
                }
            }
            val result = if (first.isSuccess) {
                ToolResult(listOf(out), "Repaired — structure rebuilt", listOf("Method" to "Cross-reference rebuild", "Pages recovered" to first.getOrThrow().toString()))
            } else {
                progress(0.4f, "Recovering pages visually")
                val pages = runCatching {
                    PageRenderer(raw).use { r ->
                        PDDocument().use { doc ->
                            var ok = 0
                            for (i in 0 until r.pageCount) {
                                progress(0.4f + 0.6f * i / r.pageCount, "Recovering page ${i + 1}")
                                val bmp = r.renderDpi(i, 170) ?: continue
                                val (w, h) = r.pageSize(i)
                                val page = PDPage(PDRectangle(w.toFloat(), h.toFloat()))
                                doc.addPage(page)
                                val img = JPEGFactory.createFromImage(doc, bmp, 0.85f)
                                PDPageContentStream(doc, page).use { it.drawImage(img, 0f, 0f, w.toFloat(), h.toFloat()) }
                                ok++
                            }
                            require(ok > 0)
                            doc.save(out); ok
                        }
                    }
                }
                if (pages.isFailure) {
                    raw.delete()
                    throw IllegalStateException("This file is too damaged to recover (${first.exceptionOrNull()?.message ?: "unreadable"})")
                }
                ToolResult(listOf(out), "Recovered as images (text is no longer selectable — run OCR to restore it)",
                    listOf("Method" to "Visual recovery", "Pages recovered" to pages.getOrThrow().toString()))
            }
            raw.delete()
            result
        }
    }
}
