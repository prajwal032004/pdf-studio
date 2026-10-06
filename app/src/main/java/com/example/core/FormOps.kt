package com.example.core

import android.content.Context
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceCharacteristicsDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDPushButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class FieldType { TEXT, MULTILINE, CHECKBOX, RADIO, DROPDOWN, LIST, SIGNATURE, BUTTON }

data class FieldInfo(
    val name: String,
    val label: String,
    val type: FieldType,
    val value: String,
    val options: List<String>,
    val readOnly: Boolean,
    val required: Boolean,
    val page: Int,
    val rect: NRect?
)

data class FieldSpec(
    val page: Int,
    val rect: NRect,
    val type: FieldType,
    val name: String,
    val options: List<String> = emptyList(),
    val defaultValue: String = "",
    val fontSize: Float = 11f
)

object FormOps {

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun typeOf(f: PDField): FieldType = when (f) {
        is PDTextField -> if (f.isMultiline) FieldType.MULTILINE else FieldType.TEXT
        is PDCheckBox -> FieldType.CHECKBOX
        is PDRadioButton -> FieldType.RADIO
        is PDComboBox -> FieldType.DROPDOWN
        is PDChoice -> FieldType.LIST
        is PDSignatureField -> FieldType.SIGNATURE
        is PDPushButton -> FieldType.BUTTON
        else -> FieldType.TEXT
    }

    fun listFields(file: File): List<FieldInfo> = runCatching {
        Workspace.load(file).use { doc ->
            val form = doc.documentCatalog.acroForm ?: return emptyList()
            form.fieldTree.filter { it.isTerminal() }.map { f ->
                val w = f.widgets.firstOrNull()
                val pageIdx = w?.page?.let { doc.pages.indexOf(it) } ?: findWidgetPage(doc, w)
                val rect = if (w != null && pageIdx >= 0) w.rectangle?.let { r ->
                    val n = PageGeometry(doc.getPage(pageIdx)).rectFromPdf(r.lowerLeftX, r.lowerLeftY, r.upperRightX, r.upperRightY)
                    NRect(n[0], n[1], n[2], n[3])
                } else null
                val options = when (f) {
                    is PDChoice -> f.optionsDisplayValues
                    is PDRadioButton -> f.exportValues.ifEmpty { f.onValues.toList() }
                    is PDCheckBox -> listOf(f.onValue)
                    else -> emptyList()
                }
                val value = when (f) {
                    is PDCheckBox -> if (f.isChecked) "true" else "false"
                    is PDChoice -> f.value.joinToString(", ")
                    else -> f.valueAsString.orEmpty()
                }
                FieldInfo(
                    f.fullyQualifiedName, f.alternateFieldName?.takeIf { it.isNotBlank() } ?: f.partialName ?: f.fullyQualifiedName,
                    typeOf(f), value, options, f.isReadOnly, f.isRequired, pageIdx, rect
                )
            }.sortedWith(compareBy({ it.page }, { it.rect?.top ?: 0f }, { it.rect?.left ?: 0f }))
        }
    }.getOrDefault(emptyList())

    private fun PDField.isTerminal() = this !is com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField

    private fun findWidgetPage(doc: PDDocument, w: PDAnnotationWidget?): Int {
        if (w == null) return -1
        doc.pages.forEachIndexed { i, p ->
            if (runCatching { p.annotations.any { it.cosObject == w.cosObject } }.getOrDefault(false)) return i
        }
        return -1
    }

    suspend fun fill(context: Context, pdf: PickedPdf, values: Map<String, String>, flatten: Boolean): File = io {
        Workspace.loadForEdit(pdf).use { doc ->
            val form = doc.documentCatalog.acroForm ?: error("This PDF has no fillable form")
            ensureDefaults(form)
            for ((name, v) in values) {
                val f = form.getField(name) ?: continue
                if (f.isReadOnly) continue
                runCatching {
                    when (f) {
                        is PDCheckBox -> if (v == "true") f.check() else f.unCheck()
                        is PDRadioButton -> if (v.isNotBlank()) f.value = v
                        is PDChoice -> if (v.isNotBlank()) f.setValue(v)
                        is PDTextField -> f.value = v
                        else -> {}
                    }
                }
            }
            if (flatten) form.flatten() else form.needAppearances = false
            val f = Workspace.newOutput(context, pdf.baseName, if (flatten) "filled_flat" else "filled")
            doc.save(f); f
        }
    }

    suspend fun flatten(context: Context, pdf: PickedPdf, alsoAnnotations: Boolean): File = io {
        Workspace.loadForEdit(pdf).use { doc ->
            val form = doc.documentCatalog.acroForm
            if (form == null && !alsoAnnotations) error("This PDF has no form fields to flatten")
            form?.let { runCatching { it.refreshAppearances() }; it.flatten() }
            if (alsoAnnotations) AnnotOps.flattenAnnotations(doc)
            val f = Workspace.newOutput(context, pdf.baseName, "flattened")
            doc.save(f); f
        }
    }

    private fun ensureDefaults(form: PDAcroForm) {
        val dr = form.defaultResources ?: PDResources().also { form.defaultResources = it }
        if (dr.getFont(COSName.getPDFName("Helv")) == null) dr.put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA)
        if (dr.getFont(COSName.getPDFName("ZaDb")) == null) dr.put(COSName.getPDFName("ZaDb"), PDType1Font.ZAPF_DINGBATS)
        if (form.defaultAppearance.isNullOrBlank()) form.defaultAppearance = "/Helv 0 Tf 0 g"
    }

    private fun rgb(r: Float, g: Float, b: Float) = PDColor(floatArrayOf(r, g, b), PDDeviceRGB.INSTANCE)

    suspend fun createFields(context: Context, pdf: PickedPdf, specs: List<FieldSpec>): File = io {
        require(specs.isNotEmpty()) { "Add at least one field" }
        Workspace.loadForEdit(pdf).use { doc ->
            val cat = doc.documentCatalog
            val form = cat.acroForm ?: PDAcroForm(doc).also { cat.acroForm = it }
            ensureDefaults(form)
            val used = form.fieldTree.map { it.fullyQualifiedName }.toMutableSet()
            for (spec in specs) {
                val page = doc.getPage(spec.page)
                val g = PageGeometry(page)
                val rc = g.rectToPdf(spec.rect.left, spec.rect.top, spec.rect.right, spec.rect.bottom)
                val rect = PDRectangle(rc[0], rc[1], rc[2], rc[3])
                var name = spec.name.ifBlank { spec.type.name.lowercase() }.replace('.', '_')
                var k = 2
                while (name in used) name = "${spec.name}_${k++}"
                used += name
                val mk = PDAppearanceCharacteristicsDictionary(COSDictionary()).apply {
                    borderColour = rgb(0.35f, 0.3f, 0.75f)
                    background = rgb(0.96f, 0.95f, 1f)
                    if (g.rotation != 0) rotation = g.rotation
                }
                when (spec.type) {
                    FieldType.TEXT, FieldType.MULTILINE -> {
                        val tf = PDTextField(form)
                        tf.partialName = name
                        tf.defaultAppearance = "/Helv ${spec.fontSize} Tf 0 g"
                        if (spec.type == FieldType.MULTILINE) tf.isMultiline = true
                        attach(page, form, tf, tf.widgets[0], rect, mk)
                        tf.value = spec.defaultValue
                    }
                    FieldType.DROPDOWN, FieldType.LIST -> {
                        val cb = if (spec.type == FieldType.DROPDOWN) PDComboBox(form) else com.tom_roush.pdfbox.pdmodel.interactive.form.PDListBox(form)
                        cb.partialName = name
                        cb.defaultAppearance = "/Helv ${spec.fontSize} Tf 0 g"
                        val opts = spec.options.ifEmpty { listOf("Option 1", "Option 2") }
                        cb.setOptions(opts)
                        attach(page, form, cb, cb.widgets[0], rect, mk)
                        cb.setValue(spec.defaultValue.takeIf { it in opts } ?: opts.first())
                    }
                    FieldType.CHECKBOX -> {
                        val box = PDCheckBox(form)
                        box.partialName = name
                        mk.normalCaption = "4"
                        val w = box.widgets[0]
                        attach(page, form, box, w, rect, mk)
                        checkboxAppearance(doc, w, rect)
                        if (spec.defaultValue == "true") box.check() else box.unCheck()
                    }
                    else -> {}
                }
            }
            val f = Workspace.newOutput(context, pdf.baseName, "form")
            doc.save(f); f
        }
    }

    private fun attach(page: PDPage, form: PDAcroForm, field: PDField, w: PDAnnotationWidget, rect: PDRectangle, mk: PDAppearanceCharacteristicsDictionary) {
        w.rectangle = rect
        w.page = page
        w.isPrinted = true
        w.appearanceCharacteristics = mk
        w.borderStyle = PDBorderStyleDictionary().apply { width = 1f }
        page.annotations.add(w)
        form.fields.add(field)
    }

    private fun checkboxAppearance(doc: PDDocument, w: PDAnnotationWidget, rect: PDRectangle) {
        fun stream(checked: Boolean): PDAppearanceStream {
            val ap = PDAppearanceStream(doc)
            ap.bBox = PDRectangle(rect.width, rect.height)
            ap.resources = PDResources()
            PDPageContentStream(doc, ap).use { cs ->
                cs.setNonStrokingColor(0.96f, 0.95f, 1f)
                cs.addRect(0f, 0f, rect.width, rect.height); cs.fill()
                cs.setStrokingColor(0.35f, 0.3f, 0.75f); cs.setLineWidth(1f)
                cs.addRect(0.5f, 0.5f, rect.width - 1, rect.height - 1); cs.stroke()
                if (checked) {
                    cs.setStrokingColor(0.1f, 0.1f, 0.1f)
                    cs.setLineWidth(minOf(rect.width, rect.height) * 0.12f)
                    cs.setLineCapStyle(1); cs.setLineJoinStyle(1)
                    cs.moveTo(rect.width * 0.2f, rect.height * 0.52f)
                    cs.lineTo(rect.width * 0.42f, rect.height * 0.28f)
                    cs.lineTo(rect.width * 0.8f, rect.height * 0.75f)
                    cs.stroke()
                }
            }
            return ap
        }
        val normal = COSDictionary()
        normal.setItem(COSName.getPDFName("Yes"), stream(true))
        normal.setItem(COSName.Off, stream(false))
        val apDict = PDAppearanceDictionary()
        apDict.cosObject.setItem(COSName.N, normal)
        w.appearance = apDict
        w.setAppearanceState("Off")
    }
}
