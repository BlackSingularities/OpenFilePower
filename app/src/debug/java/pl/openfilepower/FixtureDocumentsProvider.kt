package pl.openfilepower

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File

/** Debug-only fixture provider. Never packaged in a release APK. */
class FixtureDocumentsProvider : DocumentsProvider() {
    private val base get() = File(context!!.cacheDir, "fixtures").apply { mkdirs() }
    private fun file(id: String): File = if (id == "root") base else File(base, id.removePrefix("root/")).also { require(it.canonicalPath.startsWith(base.canonicalPath + File.separator)) }
    private fun id(file: File) = if (file == base) "root" else "root/" + file.relativeTo(base).path
    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)).apply { addRow(arrayOf("fixtures", "root", "QA fixtures", Root.FLAG_SUPPORTS_CREATE)) }
    private fun row(cursor: MatrixCursor, f: File) { val row = cursor.newRow(); cursor.columnNames.forEach { column -> row.add(column, when (column) { Document.COLUMN_DOCUMENT_ID -> id(f); Document.COLUMN_DISPLAY_NAME -> f.name; Document.COLUMN_MIME_TYPE -> if (f.isDirectory) Document.MIME_TYPE_DIR else FileRules.mime(f.name); Document.COLUMN_FLAGS -> Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or (if (f.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else 0); Document.COLUMN_SIZE -> f.length(); Document.COLUMN_LAST_MODIFIED -> f.lastModified(); else -> null }) } }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = MatrixCursor(projection ?: columns).apply { val f = file(documentId); if (f.exists()) row(this, f) }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = MatrixCursor(projection ?: columns).apply { file(parentDocumentId).listFiles()?.forEach { row(this, it) } }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor = ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val f = File(file(parentDocumentId), displayName)
        check(!f.exists())
        check(if (mimeType == Document.MIME_TYPE_DIR) f.mkdir() else f.createNewFile())
        return id(f)
    }
    override fun deleteDocument(documentId: String) { check(file(documentId).deleteRecursively()) }
    override fun renameDocument(documentId: String, displayName: String): String { val f = file(documentId); val target = File(f.parentFile, displayName); check(!target.exists() && f.renameTo(target)); return id(target) }
    override fun isChildDocument(parentDocumentId: String, documentId: String) = file(documentId).canonicalPath.startsWith(file(parentDocumentId).canonicalPath + File.separator)
}
