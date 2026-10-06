package com.example.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.MainActivity
import com.example.R
import java.util.Calendar

/** A tool that can appear on a widget: deep-link id, label, vector icon and tile colour. */
data class WidgetTool(val id: String, val label: String, val icon: Int, val tile: Int)

object WidgetActions {

    val tools = listOf(
        WidgetTool("open", "Open PDF", R.drawable.wi_open, R.drawable.wt_blue),
        WidgetTool("scan", "Scan", R.drawable.wi_scan, R.drawable.wt_teal),
        WidgetTool("merge", "Merge", R.drawable.wi_merge, R.drawable.wt_violet),
        WidgetTool("split", "Split", R.drawable.wi_split, R.drawable.wt_orange),
        WidgetTool("reorder", "Organise", R.drawable.wi_reorder, R.drawable.wt_indigo),
        WidgetTool("compress", "Compress", R.drawable.wi_compress, R.drawable.wt_green),
        WidgetTool("sign", "Sign", R.drawable.wi_sign, R.drawable.wt_pink),
        WidgetTool("annotate", "Annotate", R.drawable.wi_sign, R.drawable.wt_red),
        WidgetTool("ocr", "OCR", R.drawable.wi_ocr, R.drawable.wt_cyan),
        WidgetTool("images_to_pdf", "Img to PDF", R.drawable.wi_image, R.drawable.wt_orange),
        WidgetTool("protect", "Lock PDF", R.drawable.wi_lock, R.drawable.wt_red),
        WidgetTool("watermark", "Watermark", R.drawable.wi_watermark, R.drawable.wt_indigo),
        WidgetTool("search", "Search", R.drawable.wi_search, R.drawable.wt_cyan),
        WidgetTool("_all", "All tools", R.drawable.wi_apps, R.drawable.wt_slate)
    )

    fun tool(id: String?): WidgetTool = tools.firstOrNull { it.id == id } ?: tools[2]

    fun toolIntent(context: Context, toolId: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("pdfstudio://tools/$toolId")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun homeIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun openFileUri(uri: Uri): Uri = Uri.parse("pdfstudio://open").buildUpon().appendQueryParameter("uri", uri.toString()).build()

    fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> " · Good morning"
        in 12..16 -> " · Good afternoon"
        in 17..21 -> " · Good evening"
        else -> " · Hello"
    }

    fun relativeTime(t: Long): String {
        val d = (System.currentTimeMillis() - t) / 1000
        return when {
            d < 60 -> "just now"
            d < 3600 -> "${d / 60} min ago"
            d < 86400 -> "${d / 3600} h ago"
            d < 7 * 86400 -> "${d / 86400} d ago"
            else -> java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault()).format(java.util.Date(t))
        }
    }
}
