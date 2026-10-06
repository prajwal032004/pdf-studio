package com.example.core

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class Permissions(
    val print: Boolean = true,
    val printHighQuality: Boolean = true,
    val modify: Boolean = true,
    val copy: Boolean = true,
    val annotate: Boolean = true,
    val fillForms: Boolean = true,
    val extractForAccessibility: Boolean = true,
    val assemble: Boolean = true
) {
    fun toAccess() = AccessPermission().apply {
        setCanPrint(print)
        setCanPrintFaithful(printHighQuality)
        setCanModify(modify)
        setCanExtractContent(copy)
        setCanModifyAnnotations(annotate)
        setCanFillInForm(fillForms)
        setCanExtractForAccessibility(extractForAccessibility)
        setCanAssembleDocument(assemble)
    }

    companion object {
        val READ_ONLY = Permissions(
            print = true, printHighQuality = true, modify = false, copy = false, annotate = false,
            fillForms = false, extractForAccessibility = true, assemble = false
        )
        fun from(ap: AccessPermission) = Permissions(
            ap.canPrint(), ap.canPrintFaithful(), ap.canModify(), ap.canExtractContent(),
            ap.canModifyAnnotations(), ap.canFillInForm(), ap.canExtractForAccessibility(), ap.canAssembleDocument()
        )
    }
}

data class SecurityInfo(
    val encrypted: Boolean,
    val keyLength: Int,
    val permissions: Permissions,
    val needsUserPassword: Boolean
)

object SecurityOps {

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /**
     * Encrypts with AES. With no [userPassword] the file opens freely but the owner-password
     * restrictions still apply (a "read-only style" document).
     */
    suspend fun protect(
        context: Context, pdf: PickedPdf, userPassword: String, ownerPassword: String,
        permissions: Permissions, keyLength: Int = 256, suffix: String = "protected"
    ): File = io {
        Workspace.loadForEdit(pdf).use { doc ->
            doc.isAllSecurityToBeRemoved = false
            val owner = ownerPassword.ifBlank { userPassword.ifBlank { UUID.randomUUID().toString() } }
            val policy = StandardProtectionPolicy(owner, userPassword, permissions.toAccess())
            policy.encryptionKeyLength = keyLength
            policy.isPreferAES = true
            doc.protect(policy)
            val f = Workspace.newOutput(context, pdf.baseName, suffix)
            doc.save(f); f
        }
    }

    /** Removes all security. The picked file is already decrypted with the password the user supplied. */
    suspend fun unlock(context: Context, pdf: PickedPdf): File = io {
        Workspace.load(pdf.file).use { doc ->
            doc.isAllSecurityToBeRemoved = true
            val f = Workspace.newOutput(context, pdf.baseName, "unlocked")
            doc.save(f); f
        }
    }

    /** True when [ownerPassword] is the document's owner password (required to lift restrictions). */
    suspend fun verifyOwner(context: Context, uri: android.net.Uri, ownerPassword: String): Boolean {
        val raw = Workspace.copyToWork(context, uri)
        return io {
            try {
                runCatching { Workspace.load(raw, ownerPassword).use { !it.isEncrypted || it.currentAccessPermission.isOwnerPermission } }.getOrDefault(false)
            } finally { raw.delete() }
        }
    }

    /** Reads security details straight from the original (still encrypted) source. */
    suspend fun inspect(context: Context, uri: android.net.Uri, password: String?): SecurityInfo {
        val raw = Workspace.copyToWork(context, uri)
        return io { inspectFile(raw, password) }
    }

    private fun inspectFile(raw: File, password: String?): SecurityInfo {
        try {
            val needsUser = runCatching { Workspace.load(raw, "").close(); false }.getOrDefault(true)
            return Workspace.load(raw, password).use { d ->
                SecurityInfo(
                    d.isEncrypted,
                    d.encryption?.length ?: 0,
                    Permissions.from(d.currentAccessPermission),
                    needsUser
                )
            }
        } finally { raw.delete() }
    }
}
