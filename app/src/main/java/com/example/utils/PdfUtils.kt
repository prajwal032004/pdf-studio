package com.example.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object PdfUtils {
    
    suspend fun generateThumbnail(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
            val renderer = PdfRenderer(fd)
            if (renderer.pageCount > 0) {
                val page = renderer.openPage(0)
                val width = page.width * 2
                val height = page.height * 2
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                renderer.close()
                fd.close()
                return@withContext bitmap
            }
            renderer.close()
            fd.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }
    
    suspend fun generateThumbnailFromIndex(context: Context, uri: Uri, pageIndex: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
            val renderer = PdfRenderer(fd)
            if (pageIndex in 0 until renderer.pageCount) {
                val page = renderer.openPage(pageIndex)
                val width = page.width * 2
                val height = page.height * 2
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                renderer.close()
                fd.close()
                return@withContext bitmap
            }
            renderer.close()
            fd.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    suspend fun getPageCount(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        try {
            val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext 0
            val renderer = PdfRenderer(fd)
            val count = renderer.pageCount
            renderer.close()
            fd.close()
            return@withContext count
        } catch (e: Exception) {
            e.printStackTrace()
        }
        0
    }

    suspend fun getFileSize(context: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                return@withContext it.statSize
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        0L
    }

    suspend fun mergePdfs(context: Context, uris: List<Uri>, outputFileName: String): String? = withContext(Dispatchers.IO) {
        try {
            val merger = PDFMergerUtility()
            val outputFile = File(FileUtils.getAppFilesDir(context), outputFileName)
            
            for (uri in uris) {
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    merger.addSource(inputStream)
                }
            }
            
            val outputStream = FileOutputStream(outputFile)
            merger.destinationStream = outputStream
            merger.mergeDocuments(com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
            
            outputStream.close()
            return@withContext outputFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }
    
    suspend fun extractPages(context: Context, uri: Uri, pagesToKeep: List<Int>, outputFileName: String): String? = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            val document = PDDocument.load(inputStream)
            val newDocument = PDDocument()
            
            for (pageIndex in pagesToKeep) {
                if (pageIndex in 0 until document.numberOfPages) {
                    newDocument.addPage(document.getPage(pageIndex))
                }
            }
            
            val outputFile = File(FileUtils.getAppFilesDir(context), outputFileName)
            newDocument.save(outputFile)
            newDocument.close()
            document.close()
            inputStream.close()
            
            return@withContext outputFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }
}
