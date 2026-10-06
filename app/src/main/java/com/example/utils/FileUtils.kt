package com.example.utils

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

data class DevicePdf(
    val uri: Uri,
    val name: String,
    val size: Long,
    val dateModified: Long,
    /** Created by a tool and kept in PDF Studio's own folder. */
    val isAppFile: Boolean = false,
    /** Absolute path on disk, when known. */
    val path: String? = null,
    /** Copied to Downloads/PDF Studio with "Save to Downloads". */
    val savedByApp: Boolean = false
) {
    val fromApp: Boolean get() = isAppFile || savedByApp
    /** Human-friendly folder, e.g. "Internal storage/Download". */
    val folder: String get() = FileUtils.displayFolder(path)
}

object FileUtils {

    fun getAppFilesDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "PDF_Studio")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /** "Internal storage/Download/PDF Studio" style label for the folder that holds [path]. */
    fun displayFolder(path: String?): String {
        val parent = path?.let { File(it).parent } ?: return ""
        return displayPath(parent)
    }

    fun displayPath(dir: String): String {
        val root = Environment.getExternalStorageDirectory().absolutePath
        return when {
            dir == root -> "Internal storage"
            dir.startsWith("$root/") -> "Internal storage/" + dir.removePrefix("$root/")
            dir.startsWith("/storage/") -> "SD card/" + dir.removePrefix("/storage/").substringAfter('/', "")
            else -> dir
        }
    }

    /** Where tool results are kept, as shown to people. */
    fun appFolderLabel(context: Context): String = displayPath(getAppFilesDir(context).absolutePath)

    suspend fun getAllPdfs(context: Context): List<DevicePdf> = withContext(Dispatchers.IO) {
        val pdfs = ArrayList<DevicePdf>()
        val seen = HashSet<String>()

        // 1. Results created by PDF Studio's tools.
        val appDir = getAppFilesDir(context)
        appDir.listFiles { file -> file.isFile && file.extension.lowercase() == "pdf" }?.forEach { file ->
            seen.add(file.absolutePath)
            pdfs.add(DevicePdf(getUriForFile(context, file), file.name, file.length(), file.lastModified(), isAppFile = true, path = file.absolutePath))
        }

        // 2. Every other PDF MediaStore knows about.
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }
        @Suppress("DEPRECATION")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DATA
        )
        val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} = ? OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("application/pdf", "%.pdf")
        val savedMarker = "/" + SAVED_FOLDER + "/"

        runCatching {
            context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                @Suppress("DEPRECATION")
                val dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                while (cursor.moveToNext()) {
                    val path = if (dataCol >= 0) cursor.getString(dataCol) else null
                    if (path != null && !seen.add(path)) continue
                    val name = cursor.getString(nameCol) ?: path?.substringAfterLast('/') ?: continue
                    if (!name.endsWith(".pdf", ignoreCase = true) && path?.endsWith(".pdf", true) == false) continue
                    val size = cursor.getLong(sizeCol)
                    if (size <= 0L) continue
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol))
                    pdfs.add(
                        DevicePdf(
                            uri, name, size, cursor.getLong(dateCol) * 1000L,
                            path = path, savedByApp = path?.contains(savedMarker, ignoreCase = true) == true
                        )
                    )
                }
            }
        }.onFailure { it.printStackTrace() }

        pdfs.sortedByDescending { it.dateModified }
    }

    private const val SAVED_FOLDER = "Download/PDF Studio"

    suspend fun saveToAppFiles(context: Context, sourceFilePath: String, fileName: String): File? = withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(sourceFilePath)
            if (!sourceFile.exists()) return@withContext null

            val dir = getAppFilesDir(context)
            var destFile = File(dir, fileName)
            var counter = 1
            while (destFile.exists()) {
                val nameWithoutExt = fileName.substringBeforeLast(".")
                destFile = File(dir, "${nameWithoutExt}_($counter).pdf")
                counter++
            }

            sourceFile.copyTo(destFile, overwrite = true)
            return@withContext destFile
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    suspend fun saveFileToUri(context: Context, sourceFilePath: String, destinationUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(sourceFilePath)
            if (!sourceFile.exists()) return@withContext false
            val outputStream = context.contentResolver.openOutputStream(destinationUri) ?: return@withContext false
            FileInputStream(sourceFile).use { input -> outputStream.use { input.copyTo(it) } }
            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }

    fun getFileName(context: Context, uri: Uri): String {
        var name = "unknown.pdf"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex != -1) {
                    name = cursor.getString(nameIndex)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return name
    }

    fun getUriForFile(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }
}

/**
 * One shared, in-memory list of the PDFs on this device. Files, Storage and Settings all
 * read it, so switching between them shows results instantly while a refresh runs quietly.
 */
object PdfIndex {
    private val _files = MutableStateFlow<List<DevicePdf>?>(null)
    val files: StateFlow<List<DevicePdf>?> = _files.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val mutex = Mutex()

    suspend fun refresh(context: Context) {
        if (mutex.isLocked) { mutex.withLock { }; return } // a scan is already running; just wait for it
        mutex.withLock {
            _loading.value = true
            try { _files.value = FileUtils.getAllPdfs(context.applicationContext) } finally { _loading.value = false }
        }
    }

    /** Updates the list right away after a delete, before the next full scan. */
    fun removeLocal(uris: Collection<Uri>) {
        val gone = uris.toSet()
        _files.value = _files.value?.filterNot { it.uri in gone }
    }
}
