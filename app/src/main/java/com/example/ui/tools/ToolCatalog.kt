package com.example.ui.tools

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.ui.theme.Accents

enum class ToolCategory(val title: String, val subtitle: String, val icon: ImageVector, val accent: Color) {
    BASIC("Basics", "Open, create, print & inspect", Icons.Rounded.Description, Accents.blue),
    PAGES("Pages", "Organise every page", Icons.Rounded.AutoAwesomeMosaic, Accents.violet),
    EDIT("Edit & Annotate", "Mark up, sign & stamp", Icons.Rounded.Draw, Accents.pink),
    CONVERT("Convert", "To and from PDF", Icons.Rounded.SwapHoriz, Accents.orange),
    COMPRESS("Compress", "Smaller files, same look", Icons.Rounded.Compress, Accents.green),
    SECURITY("Security", "Passwords & permissions", Icons.Rounded.Lock, Accents.red),
    OCR("OCR & Scan", "Make scans searchable", Icons.Rounded.DocumentScanner, Accents.teal),
    ORGANIZE("Organise", "Bookmarks, numbers & marks", Icons.Rounded.Bookmarks, Accents.indigo),
    SEARCH("Search & Text", "Find, count & replace", Icons.Rounded.ManageSearch, Accents.cyan),
    IMAGES("Page & Image", "Crop, resize & recolour", Icons.Rounded.Crop, Accents.amber),
    ADVANCED("Advanced", "Redact, forms, verify & more", Icons.Rounded.AutoAwesome, Accents.slate)
}

/** How a tool is opened: picks a PDF first (most), needs no input, or has its own entry flow. */
enum class ToolInput { PDF, NONE, MULTI }

data class ToolDef(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val category: ToolCategory,
    val input: ToolInput = ToolInput.PDF,
    val keywords: String = "",
    val isNew: Boolean = false
)

object ToolCatalog {
    private val B = ToolCategory.BASIC
    private val P = ToolCategory.PAGES
    private val E = ToolCategory.EDIT
    private val C = ToolCategory.CONVERT
    private val Z = ToolCategory.COMPRESS
    private val S = ToolCategory.SECURITY
    private val O = ToolCategory.OCR
    private val G = ToolCategory.ORGANIZE
    private val T = ToolCategory.SEARCH
    private val I = ToolCategory.IMAGES
    private val A = ToolCategory.ADVANCED

    val all: List<ToolDef> = listOf(
        // Basics
        ToolDef("open", "Open PDF", "View, search & read", Icons.Rounded.FileOpen, B, ToolInput.NONE, "view viewer read"),
        ToolDef("images_to_pdf", "Images to PDF", "Photos into one PDF", Icons.Rounded.Collections, B, ToolInput.NONE, "jpg png photo gallery create"),
        ToolDef("scan", "Scan Document", "Camera with auto-crop", Icons.Rounded.DocumentScanner, B, ToolInput.NONE, "camera capture perspective auto crop", isNew = true),
        ToolDef("create_blank", "Blank PDF", "Start from empty pages", Icons.Rounded.NoteAdd, B, ToolInput.NONE, "new create empty"),
        ToolDef("print", "Print PDF", "Send to any printer", Icons.Rounded.Print, B, keywords = "printer"),
        ToolDef("properties", "PDF Properties", "Info, fonts & metadata", Icons.Rounded.Info, B, keywords = "metadata info details viewer fonts"),
        ToolDef("metadata", "Edit Metadata", "Title, author & more", Icons.Rounded.EditNote, B, keywords = "title author subject keywords"),

        // Pages
        ToolDef("merge", "Merge PDFs", "Combine into one file", Icons.Rounded.MergeType, P, ToolInput.MULTI, "combine join"),
        ToolDef("split", "Split PDF", "By range, every N, or pages", Icons.AutoMirrored.Rounded.CallSplit, P, keywords = "separate divide"),
        ToolDef("reorder", "Organise Pages", "Drag to reorder", Icons.Rounded.DragIndicator, P, keywords = "reorder arrange sort move"),
        ToolDef("rotate", "Rotate Pages", "Fix page orientation", Icons.AutoMirrored.Rounded.RotateRight, P, keywords = "turn"),
        ToolDef("delete_pages", "Delete Pages", "Remove unwanted pages", Icons.Rounded.DeleteSweep, P, keywords = "remove"),
        ToolDef("duplicate_pages", "Duplicate Pages", "Copy pages in place", Icons.Rounded.ContentCopy, P, keywords = "copy clone"),
        ToolDef("add_pages", "Add Blank Pages", "Insert empty pages", Icons.Rounded.AddBox, P, keywords = "insert blank"),
        ToolDef("extract_pages", "Extract Pages", "Save selected pages", Icons.Rounded.Output, P, keywords = "select save pages"),
        ToolDef("insert_pages", "Insert from PDF", "Add pages from another file", Icons.Rounded.PostAdd, P, keywords = "import combine"),
        ToolDef("replace_pages", "Replace Pages", "Swap pages from another PDF", Icons.Rounded.FindReplace, P, keywords = "swap substitute"),
        ToolDef("move_pages", "Move Between PDFs", "Transfer pages across files", Icons.Rounded.MoveUp, P, keywords = "transfer"),

        // Edit
        ToolDef("annotate", "Annotate & Draw", "Pen, shapes, highlights", Icons.Rounded.Draw, E, keywords = "pen pencil draw freehand markup highlight underline strikethrough shapes arrow line eraser"),
        ToolDef("sign", "Sign PDF", "Draw & place signature", Icons.Rounded.HistoryEdu, E, keywords = "signature autograph"),
        ToolDef("add_text", "Add Text", "Text boxes anywhere", Icons.Rounded.TextFields, E, keywords = "type write textbox"),
        ToolDef("highlight", "Highlight Text", "Highlight, underline, strike", Icons.Rounded.BorderColor, E, keywords = "marker underline strike"),
        ToolDef("comments", "Comments & Notes", "Sticky notes on pages", Icons.Rounded.Comment, E, keywords = "note sticky comment"),
        ToolDef("stamps", "Stamps & Dates", "Approved, date/time stamps", Icons.Rounded.Approval, E, keywords = "stamp approved date time"),
        ToolDef("add_image", "Add Image", "Place pictures & logos", Icons.Rounded.AddPhotoAlternate, E, keywords = "picture logo photo"),

        // Convert
        ToolDef("pdf_to_images", "PDF to Images", "PNG/JPG per page", Icons.Rounded.Image, C, keywords = "jpg png export"),
        ToolDef("pdf_to_text", "PDF to Text", "Export all text", Icons.AutoMirrored.Rounded.TextSnippet, C, keywords = "txt extract"),
        ToolDef("text_to_pdf", "Text to PDF", "Type or import .txt", Icons.AutoMirrored.Rounded.Notes, C, ToolInput.NONE, "txt"),
        ToolDef("markdown_to_pdf", "Markdown to PDF", "Styled from .md", Icons.Rounded.Code, C, ToolInput.NONE, "md readme"),
        ToolDef("html_to_pdf", "HTML to PDF", "Render HTML offline", Icons.Rounded.Html, C, ToolInput.NONE, "web html"),
        ToolDef("webpage_to_pdf", "Saved Webpage to PDF", "From a local .html file", Icons.Rounded.Public, C, ToolInput.NONE, "webpage saved offline mht"),

        // Compress
        ToolDef("compress", "Compress PDF", "Reduce file size", Icons.Rounded.Compress, Z, keywords = "reduce shrink optimize size"),
        ToolDef("image_quality", "Image Quality", "Resolution & JPEG quality", Icons.Rounded.HighQuality, Z, keywords = "resolution dpi jpeg"),
        ToolDef("remove_metadata", "Remove Metadata", "Strip hidden info", Icons.Rounded.CleaningServices, Z, keywords = "privacy clean strip"),

        // Security
        ToolDef("protect", "Add Password", "AES-256 encryption", Icons.Rounded.Lock, S, keywords = "encrypt password secure"),
        ToolDef("unlock", "Remove Password", "Unlock with your password", Icons.Rounded.LockOpen, S, keywords = "decrypt unlock"),
        ToolDef("permissions", "Permissions", "Restrict print, copy, edit", Icons.Rounded.AdminPanelSettings, S, keywords = "restrict read only copy print"),

        // OCR
        ToolDef("ocr", "OCR — Searchable PDF", "Recognise text in scans", Icons.Rounded.TextRotationNone, O, keywords = "ocr recognize searchable scan"),
        ToolDef("ocr_text", "Extract Scanned Text", "OCR to plain text", Icons.Rounded.Abc, O, keywords = "ocr text"),
        ToolDef("orientation", "Auto Orientation", "Detect & fix upside-down", Icons.Rounded.ScreenRotation, O, keywords = "rotate detect upside"),
        ToolDef("deskew", "Deskew Scans", "Straighten tilted pages", Icons.Rounded.Straighten, O, keywords = "straighten tilt"),
        ToolDef("remove_blank", "Remove Blank Pages", "Drop empty scanned pages", Icons.Rounded.LayersClear, O, keywords = "blank empty"),

        // Organise
        ToolDef("bookmarks", "Bookmarks", "Add, edit & remove", Icons.Rounded.Bookmarks, G, keywords = "outline chapters"),
        ToolDef("toc", "Table of Contents", "Clickable contents page", Icons.Rounded.List, G, keywords = "contents links index"),
        ToolDef("page_labels", "Page Labels", "i, ii, iii… A-1…", Icons.Rounded.Label, G, keywords = "roman numbering labels"),
        ToolDef("header_footer", "Header & Footer", "Text on every page", Icons.Rounded.VerticalAlignTop, G, keywords = "header footer"),
        ToolDef("page_numbers", "Page Numbers", "Number every page", Icons.Rounded.FormatListNumbered, G, keywords = "numbering"),
        ToolDef("watermark", "Watermark", "Text or image overlay", Icons.Rounded.BrandingWatermark, G, keywords = "confidential draft logo"),
        ToolDef("bates", "Bates Numbering", "Legal page IDs", Icons.Rounded.Tag, G, keywords = "legal bates"),

        // Search
        ToolDef("search", "Search & Highlight", "Find every occurrence", Icons.Rounded.ManageSearch, T, keywords = "find"),
        ToolDef("word_count", "Word Count", "Words, characters, pages", Icons.Rounded.Numbers, T, keywords = "count statistics"),
        ToolDef("replace_text", "Find & Replace", "Edit text in place", Icons.Rounded.FindReplace, T, keywords = "replace edit"),

        // Page & image
        ToolDef("crop", "Crop Pages", "Trim margins or auto-crop", Icons.Rounded.Crop, I, keywords = "trim margins"),
        ToolDef("resize", "Resize Pages", "A4, Letter, custom", Icons.Rounded.AspectRatio, I, keywords = "page size a4 letter scale"),
        ToolDef("grayscale", "Grayscale", "Convert colour to gray", Icons.Rounded.Contrast, I, keywords = "black white monochrome"),
        ToolDef("manage_images", "Embedded Images", "Extract, replace, remove", Icons.Rounded.PhotoLibrary, I, keywords = "extract replace remove images"),

        // Advanced
        ToolDef("redact", "Redact", "Black out content forever", Icons.Rounded.FormatStrikethrough, A, keywords = "censor hide black"),
        ToolDef("auto_redact", "Find Sensitive Info", "Emails, phones, IDs → redact", Icons.Rounded.PrivacyTip, A, keywords = "pii sensitive detect", isNew = true),
        ToolDef("fill_form", "Fill Form", "Complete AcroForms", Icons.Rounded.EditNote, A, keywords = "acroform fill fields"),
        ToolDef("create_form", "Create Form", "Add fillable fields", Icons.Rounded.DynamicForm, A, keywords = "fields checkbox textbox"),
        ToolDef("flatten", "Flatten", "Lock forms & annotations", Icons.Rounded.Layers, A, keywords = "flatten"),
        ToolDef("verify_signatures", "Verify Signatures", "Check digital signatures", Icons.Rounded.VerifiedUser, A, keywords = "digital certificate detect"),
        ToolDef("pdfa", "PDF/A", "Validate & convert for archiving", Icons.Rounded.Inventory2, A, keywords = "archive pdfa validate"),
        ToolDef("duplicates", "Duplicate Pages", "Find & remove repeats", Icons.Rounded.FilterNone, A, keywords = "duplicate detect"),
        ToolDef("blank_pages", "Detect Blank Pages", "Find empty pages", Icons.Rounded.CheckBoxOutlineBlank, A, keywords = "blank detect"),
        ToolDef("barcodes", "QR & Barcodes", "Detect & read codes", Icons.Rounded.QrCodeScanner, A, keywords = "qr barcode scan"),
        ToolDef("batch", "Batch Process", "One action, many PDFs", Icons.Rounded.DynamicFeed, A, ToolInput.MULTI, "bulk many multiple"),
        ToolDef("repair", "Repair PDF", "Recover damaged files", Icons.Rounded.Healing, A, ToolInput.NONE, "fix corrupt recover")
    )

    private val byId = all.associateBy { it.id }
    fun get(id: String): ToolDef? = byId[id]

    fun inCategory(c: ToolCategory) = all.filter { it.category == c }

    fun search(q: String): List<ToolDef> {
        if (q.isBlank()) return all
        val t = q.trim().lowercase()
        return all.filter {
            it.title.lowercase().contains(t) || it.subtitle.lowercase().contains(t) ||
                it.keywords.contains(t) || it.category.title.lowercase().contains(t)
        }
    }

    /** Tools offered on a PDF that is already open (viewer "Open with" sheet). */
    val forOpenPdf = listOf(
        "annotate", "sign", "add_text", "highlight", "compress", "merge", "split", "reorder", "rotate",
        "delete_pages", "extract_pages", "protect", "unlock", "ocr", "watermark", "page_numbers", "pdf_to_images",
        "pdf_to_text", "redact", "auto_redact", "fill_form", "crop", "grayscale", "bookmarks", "header_footer",
        "metadata", "properties", "print", "verify_signatures", "pdfa", "barcodes", "word_count", "search",
        "remove_blank", "duplicates", "flatten", "resize", "manage_images", "toc", "bates", "page_labels",
        "permissions", "insert_pages", "replace_pages", "duplicate_pages", "add_pages", "image_quality",
        "remove_metadata", "orientation", "deskew", "ocr_text", "replace_text", "create_form", "stamps",
        "comments", "add_image", "move_pages", "blank_pages"
    ).mapNotNull { get(it) }
}
