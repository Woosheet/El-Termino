package dev.eltermino.android

import android.content.ContentProvider
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.test.mock.MockContentResolver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.eltermino.app.LocalFileSystem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Documents as the system picker hands them out: a provider may not say the name or the size. */
class TestDocumentsProvider : ContentProvider() {
    private fun file() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "document.bin")

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? {
        if (uri.lastPathSegment == "gone") return null
        val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        cursor.addRow(
            when (uri.lastPathSegment) {
                "known" -> arrayOf<Any?>("nota.txt", file().length())
                "nosize" -> arrayOf<Any?>("nota.txt", null)
                "slash" -> arrayOf<Any?>("../../nota.txt", file().length())
                else -> arrayOf<Any?>(null, null)
            },
        )
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(file(), ParcelFileDescriptor.parseMode(mode))
    override fun getType(uri: Uri) = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}

private const val AUTHORITY = "dev.eltermino.android.test.documents"

@RunWith(AndroidJUnit4::class)
class DocumentFilesOnAndroidTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = MockContentResolver().apply {
        addProvider(AUTHORITY, TestDocumentsProvider().apply { attachInfo(target, ProviderInfo().apply { authority = AUTHORITY; exported = true }) })
    }
    private val context = object : ContextWrapper(target) {
        override fun getContentResolver() = resolver
    }
    private val fs = DocumentFileSystem(context, LocalFileSystem(context.cacheDir))
    private val document = File(context.cacheDir, "document.bin").apply { writeBytes(ByteArray(1000) { it.toByte() }) }

    private fun uri(name: String) = "content://$AUTHORITY/$name"

    @Test
    fun uploadFromContentUri() = runBlocking {
        val file = fs.stat(uri("known"))!!
        assertEquals("nota.txt", file.name)
        assertEquals(1000L, file.size)
        val source = fs.openRead(uri("known"))
        val buffer = ByteArray(4096)
        val read = source.read(buffer)
        source.close()
        assertArrayEquals(document.readBytes(), buffer.copyOf(read))
    }

    @Test
    fun unknownSizeAndMissingName() = runBlocking {
        assertEquals(0L, fs.stat(uri("nosize"))!!.size)
        assertEquals("file", fs.stat(uri("noname"))!!.name)
    }

    @Test
    fun aNameCannotLeaveTheFolder() = runBlocking {
        assertEquals("nota.txt", fs.stat(uri("slash"))!!.name)
    }

    @Test
    fun readingResumesFromAnOffset() = runBlocking {
        val source = fs.openRead(uri("known"), 990)
        val buffer = ByteArray(4096)
        assertEquals(10, source.read(buffer))
        source.close()
        assertEquals(990.toByte().toInt(), buffer[0].toInt())
    }

    @Test
    fun writingReplacesTheDocument() = runBlocking {
        val sink = fs.openWrite(uri("known"))
        sink.write(byteArrayOf(1, 2, 3))
        sink.close()
        assertArrayEquals(byteArrayOf(1, 2, 3), document.readBytes())
    }

    @Test
    fun aDocumentThatIsGoneIsNull() = runBlocking {
        assertNull(fs.stat(uri("gone")))
    }

    @Test
    fun plainPathsStayLocal() = runBlocking {
        val plain = File(context.cacheDir, "plain.txt").apply { writeText("x") }
        assertEquals(1L, fs.stat(plain.path)!!.size)
    }
}
