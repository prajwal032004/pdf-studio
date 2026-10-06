package com.example.core

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.utils.DevicePdf
import com.example.utils.FileUtils
import com.example.utils.PdfIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

sealed class DeleteResult {
    data class Done(val deleted: Int, val failed: Int) : DeleteResult()
    /** Android needs the person to confirm; launch [sender], then refresh. */
    data class NeedsConsent(val sender: IntentSender, val deletedSoFar: Int, val pending: List<Uri>) : DeleteResult()
}

data class DuplicateGroup(val files: List<DevicePdf>) {
    val size: Long get() = files.first().size
    val reclaimable: Long get() = size * (files.size - 1)
}

data class FolderStat(val folder: String, val count: Int, val bytes: Long)

data class StorageStats(
    val count: Int,
    val bytes: Long,
    val appCount: Int,
    val appBytes: Long,
    val savedCount: Int,
    val savedBytes: Long,
    val folders: List<FolderStat>
)

/** Delete, rename and analyse PDFs on the device. */
object FileOps {

    private fun isMediaUri(uri: Uri) = uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY

    suspend fun delete(context: Context, items: List<DevicePdf>): DeleteResult = withContext(Dispatchers.IO) {
        var ok = 0
        var failed = 0
        val consent = mutableListOf<Uri>()
        var recoverable: IntentSender? = null
        val removed = mutableListOf<Uri>()
        for (item in items) {
            val gone = try {
                deleteOne(context, item)
            } catch (e: SecurityException) {
                if (isMediaUri(item.uri)) {
                    consent += item.uri
                    if (Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException && recoverable == null) {
                        recoverable = e.userAction.actionIntent.intentSender
                    }
                }
                false
            } catch (e: Exception) {
                false
            }
            when {
                gone -> { ok++; removed += item.uri; forget(context, item) }
                item.uri !in consent -> failed++
            }
        }
        PdfIndex.removeLocal(removed)
        if (consent.isNotEmpty()) {
            val sender = if (Build.VERSION.SDK_INT >= 30) {
                runCatching { MediaStore.createDeleteRequest(context.contentResolver, consent).intentSender }.getOrNull()
            } else recoverable.takeIf { consent.size == 1 }
            if (sender != null) return@withContext DeleteResult.NeedsConsent(sender, ok, consent)
            failed += consent.size
        }
        DeleteResult.Done(ok, failed)
    }

    private fun deleteOne(context: Context, item: DevicePdf): Boolean {
        val file = item.path?.let(::File)
        if (item.isAppFile && file != null) return file.delete() || !file.exists()
        if (isMediaUri(item.uri)) {
            // With all-files access (or for files we created) this removes the entry and the file together.
            if (context.contentResolver.delete(item.uri, null, null) > 0) {
                if (file != null && file.exists()) file.delete()
                return true
            }
        }
        if (file != null && file.exists() && file.delete()) {
            MediaScannerConnection.scanFile(context, arrayOf(file.path), null, null)
            return true
        }
        return file != null && !file.exists()
    }

    /** Drops every trace of a removed or renamed file from recents and caches. */
    fun forget(context: Context, item: DevicePdf) {
        RecentStore.remove(context, item.uri)
        Workspace.invalidate(item.uri)
    }

    /** Called after the system delete dialog was accepted. */
    fun afterConsent(context: Context, uris: List<Uri>) {
        uris.forEach { RecentStore.remove(context, it); Workspace.invalidate(it) }
        PdfIndex.removeLocal(uris)
    }

    fun sanitizeName(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").removeSuffix(".pdf").removeSuffix(".PDF").trim().take(120)

    /** Renames a PDF. Returns the new display name. */
    suspend fun rename(context: Context, item: DevicePdf, newName: String): String = withContext(Dispatchers.IO) {
        val base = sanitizeName(newName)
        require(base.isNotBlank()) { "Enter a name" }
        val target = "$base.pdf"
        if (target == item.name) return@withContext target
        val file = item.path?.let(::File)
        if (file != null && file.exists() && file.canWrite()) {
            val dest = File(file.parentFile, target)
            if (dest.exists()) error("A file named \"$target\" already exists here")
            if (file.renameTo(dest)) {
                if (!item.isAppFile) MediaScannerConnection.scanFile(context, arrayOf(file.path, dest.path), null, null)
                forget(context, item)
                return@withContext target
            }
        }
        if (Build.VERSION.SDK_INT >= 29 && isMediaUri(item.uri)) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, target) }
            if (runCatching { context.contentResolver.update(item.uri, values, null, null) }.getOrDefault(0) > 0) {
                forget(context, item)
                return@withContext target
            }
        }
        error("PDF Studio isn't allowed to rename this file. Grant \"All files access\" in Files and try again.")
    }

    fun stats(context: Context, all: List<DevicePdf>): StorageStats {
        val app = all.filter { it.isAppFile }
        val saved = all.filter { it.savedByApp }
        val folders = all.groupBy { it.folder.ifBlank { "Other" } }
            .map { (f, l) -> FolderStat(f, l.size, l.sumOf { it.size }) }
            .sortedByDescending { it.bytes }
        return StorageStats(all.size, all.sumOf { it.size }, app.size, app.sumOf { it.size }, saved.size, saved.sumOf { it.size }, folders)
    }

    /** Finds byte-for-byte identical PDFs: same size first, then a full content hash to be sure. */
    suspend fun findDuplicates(
        context: Context,
        all: List<DevicePdf>,
        progress: (done: Int, total: Int) -> Unit
    ): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val candidates = all.filter { it.size > 0 }.groupBy { it.size }.values.filter { it.size > 1 }
        val total = candidates.sumOf { it.size }
        var done = 0
        progress(0, total)
        val groups = mutableListOf<DuplicateGroup>()
        for (sameSize in candidates) {
            val byHash = HashMap<String, MutableList<DevicePdf>>()
            for (f in sameSize) {
                ensureActive()
                hash(context, f.uri)?.let { byHash.getOrPut(it) { mutableListOf() }.add(f) }
                done++
                progress(done, total)
            }
            byHash.values.filter { it.size > 1 }.forEach { groups += DuplicateGroup(it.sortedBy { f -> f.dateModified }) }
        }
        groups.sortedByDescending { it.reclaimable }
    }

    private fun hash(context: Context, uri: Uri): String? = runCatching {
        val md = MessageDigest.getInstance("MD5")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    fun appFolder(context: Context): String = FileUtils.appFolderLabel(context)
}
