package com.example.ui.tools

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.rounded.FileOpen
import com.example.ui.common.LocalNav
import com.example.ui.common.rememberPdfPicker
import com.example.ui.editor.EditorScreen
import com.example.ui.tools.screens.*

/** Routes a tool id to its screen. [uri] pre-loads a PDF (from the viewer, a widget or a share). */
@Composable
fun ToolHost(id: String, uri: Uri?) {
    when (id) {
        "open" -> OpenPdfLauncher()
        "images_to_pdf" -> ImagesToPdfScreen(Incoming.take())
        "scan" -> ScanScreen()
        "create_blank" -> CreateBlankScreen()
        "print" -> PrintScreen(uri)
        "properties" -> PropertiesScreen(uri)
        "metadata", "remove_metadata" -> MetadataScreen(id, uri)

        "merge" -> MergeScreen(uri)
        "split" -> SplitScreen(uri)
        "reorder", "rotate", "delete_pages", "duplicate_pages" -> PageOrganizerScreen(id, uri)
        "add_pages" -> AddPagesScreen(uri)
        "extract_pages" -> ExtractPagesScreen(uri)
        "insert_pages" -> InsertPagesScreen(uri)
        "replace_pages" -> ReplacePagesScreen(uri)
        "move_pages" -> MovePagesScreen(uri)

        "annotate", "sign", "add_text", "highlight", "comments", "stamps", "add_image" -> EditorScreen(id, uri)

        "pdf_to_images" -> PdfToImagesScreen(uri)
        "pdf_to_text" -> PdfToTextScreen(uri)
        "text_to_pdf" -> TextToPdfScreen(TextKind.TEXT, uri, Incoming.takeText())
        "markdown_to_pdf" -> TextToPdfScreen(TextKind.MARKDOWN, uri, Incoming.takeText())
        "html_to_pdf" -> TextToPdfScreen(TextKind.HTML, uri, Incoming.takeText())
        "webpage_to_pdf" -> TextToPdfScreen(TextKind.WEBPAGE, uri)

        "compress", "image_quality" -> CompressScreen(id, uri)

        "protect" -> ProtectScreen(uri)
        "unlock" -> UnlockScreen(uri)
        "permissions" -> PermissionsScreen(uri)

        "ocr" -> OcrScreen(uri)
        "ocr_text" -> OcrTextScreen(uri)
        "orientation", "remove_blank", "blank_pages" -> AnalyseAndFixScreen(id, uri)
        "deskew" -> DeskewScreen(uri)

        "bookmarks" -> BookmarksScreen(uri)
        "toc" -> TocScreen(uri)
        "page_labels" -> PageLabelsScreen(uri)
        "header_footer" -> HeaderFooterScreen(uri)
        "page_numbers" -> PageNumbersScreen(uri)
        "watermark" -> WatermarkScreen(uri)
        "bates" -> BatesScreen(uri)

        "search" -> SearchScreen(uri)
        "word_count" -> WordCountScreen(uri)
        "replace_text" -> ReplaceTextScreen(uri)

        "crop" -> CropScreen(uri)
        "resize" -> ResizeScreen(uri)
        "grayscale" -> GrayscaleScreen(uri)
        "manage_images" -> ManageImagesScreen(uri)

        "redact", "auto_redact" -> RedactScreen(id, uri)
        "fill_form" -> FillFormScreen(uri)
        "create_form" -> CreateFormScreen(uri)
        "flatten" -> FlattenScreen(uri)
        "verify_signatures" -> VerifySignaturesScreen(uri)
        "pdfa" -> PdfAScreen(uri)
        "duplicates" -> DuplicatesScreen(uri)
        "barcodes" -> BarcodesScreen(uri)
        "batch" -> BatchScreen()
        "repair" -> RepairScreen()
        else -> AllToolsScreen()
    }
}

/** "Open PDF" tool: shows the picker, then the viewer. */
@Composable
private fun OpenPdfLauncher() {
    val nav = LocalNav.current
    val picker = rememberPdfPicker { p -> nav.back(); nav.openUri(p.uri) }
    LaunchedEffect(Unit) { picker.launch() }
    com.example.ui.common.EmptyPick(
        androidx.compose.material.icons.Icons.Rounded.FileOpen, "Open a PDF", "Pick any PDF on your device to view, search, annotate and more.",
        "Choose PDF", picker.loading, com.example.ui.theme.Accents.blue
    ) { picker.launch() }
}
