package pl.openfilepower

import android.content.Intent
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.ViewModelProvider
import android.graphics.Bitmap
import android.graphics.Point
import androidx.exifinterface.media.ExifInterface
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExplorerDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val uri = DocumentsContract.buildTreeDocumentUri("pl.openfilepower.test.documents", "root")
    private val local = Root("qa", "Moje pliki", uri.toString())
    @Before fun disableUpdateChecks() { Preferences(context).autoUpdate = false }
    private fun awaitCondition(condition: () -> Boolean) {
        val until = android.os.SystemClock.elapsedRealtime() + 15000
        while (!condition() && android.os.SystemClock.elapsedRealtime() < until) android.os.SystemClock.sleep(100)
        assertTrue("Condition did not become true", condition())
    }
    private fun workspace(): Pair<Files, Place> {
        val files = Files(context) { listOf(local) }
        return files to files.mkdir(Place(local.id), "test-${UUID.randomUUID()}")
    }
    private fun write(files: Files, parent: Place, name: String, bytes: ByteArray): Item {
        val doc = files.document(parent).createFile("application/octet-stream", name)!!
        context.contentResolver.openOutputStream(doc.uri)!!.use { it.write(bytes) }
        return files.list(parent).first { it.name == name }
    }
    @Test fun safTransfersPreserveBytesAndProtectExistingFiles() {
        val (files, base) = workspace()
        val source = files.mkdir(base, "Źródło")
        val dest = files.mkdir(base, "Cel")
        val bytes = ByteArray(400_123) { (it % 251).toByte() }
        val item = write(files, source, "zdjęcie.bin", bytes)
        files.transfer(Clip(listOf(item), false), dest, { false }) { _, _ -> }
        assertArrayEquals(bytes, files.input(files.list(dest).single()).use { it.readBytes() })
        assertTrue(runCatching { files.transfer(Clip(listOf(item), false), dest, { false }) { _, _ -> } }.isFailure)
        assertArrayEquals(bytes, files.input(files.list(dest).single()).use { it.readBytes() })
        assertEquals(1, files.list(source).size)
        files.rename(files.list(dest).single(), "nowa.bin")
        assertEquals("nowa.bin", files.list(dest).single().name)
        files.delete(files.list(dest).single())
        assertTrue(files.list(dest).isEmpty())
        files.transfer(Clip(listOf(item), true), dest, { false }) { _, _ -> }
        assertTrue(files.list(source).isEmpty())
        assertArrayEquals(bytes, files.input(files.list(dest).single()).use { it.readBytes() })
    }
    @Test fun recursiveCopyRejectsSelfAndCleansCancelledOutput() {
        val (files, base) = workspace()
        val source = files.mkdir(base, "source")
        val dest = files.mkdir(base, "dest")
        write(files, source, "large.bin", ByteArray(600_000) { 42 })
        val folder = files.list(base).first { it.name == "source" }
        assertTrue(runCatching { files.transfer(Clip(listOf(folder), false), source, { false }) { _, _ -> } }.isFailure)
        var cancelled = false
        assertTrue(runCatching { files.transfer(Clip(listOf(folder), false), dest, { cancelled }) { _, _ -> cancelled = true } }.isFailure)
        assertTrue(files.list(dest).isEmpty())
        assertEquals(1, files.list(source).size)
        files.transfer(Clip(listOf(folder), false), dest, { false }) { _, _ -> }
        assertEquals(600_000L, files.list(dest.child("source")).single().size)
    }
    @Test fun overlappingSafRootsCannotCopyIntoThemselves() {
        val alias = local.copy(id = "alias")
        val files = Files(context) { listOf(local, alias) }
        val parent = files.mkdir(Place(local.id), "alias-${UUID.randomUUID()}")
        val source = files.mkdir(parent, "source")
        files.mkdir(source, "child")
        val item = files.list(parent).single()
        val destination = Place(alias.id, source.parts + "child")
        assertTrue(runCatching { files.transfer(Clip(listOf(item), false), destination, { false }) { _, _ -> } }.isFailure)
        assertTrue(files.list(source.child("child")).isEmpty())
    }
    @Test fun smbRoundTrip() {
        val nas = Root("nas", "Test NAS", "smb://10.0.2.2/fixtures/", "ofptest", "", "fixture-password")
        val files = Files(context) { listOf(local, nas) }
        val source = files.mkdir(Place(local.id), "network-${UUID.randomUUID()}")
        val target = files.mkdir(Place(nas.id), "test-${UUID.randomUUID()}")
        val bytes = ByteArray(300_001) { (it % 127).toByte() }
        val item = write(files, source, "żółć 100% #1.bin", bytes)
        files.transfer(Clip(listOf(item), false), target, { false }) { _, _ -> }
        val remote = files.list(target).single()
        assertEquals(item.name, remote.name)
        assertArrayEquals(bytes, files.input(remote).use { it.readBytes() })
        val back = files.mkdir(source, "back")
        files.transfer(Clip(listOf(remote), true), back, { false }) { _, _ -> }
        assertArrayEquals(bytes, files.input(files.list(back).single()).use { it.readBytes() })
        assertTrue(files.list(target).isEmpty())
    }
    @Test fun inspectorReadsPhotoExif() {
        val (files, base) = workspace()
        val doc = files.document(base).createFile("image/jpeg", "sample.jpg")!!
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(80, 160, 140)) }
        context.contentResolver.openOutputStream(doc.uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        context.contentResolver.openFileDescriptor(doc.uri, "rw")!!.use {
            ExifInterface(it.fileDescriptor).apply {
                setAttribute(ExifInterface.TAG_MODEL, "QA Camera")
                setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "400")
                saveAttributes()
            }
        }
        Preferences(context).apply { saveRoots(listOf(local)); savePlaces("tabs", listOf(base)) }
        val item = files.list(base).single()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: ExplorerModel
            scenario.onActivity { model = ViewModelProvider(it)[ExplorerModel::class.java] }
            awaitCondition { !model.loading }
            scenario.onActivity { model.inspect(item) }
            awaitCondition { model.details?.values?.any { it.first == "Aparat" } == true }
            assertTrue(model.details!!.values.contains("Aparat" to "QA Camera"))
            assertTrue(model.details!!.values.contains("ISO" to "400"))
            assertNotNull(model.details!!.preview)
        }
    }
    @Test fun dragAndDropCopiesAFile() {
        val (files, base) = workspace()
        val target = files.mkdir(base, "Upuść tutaj")
        val bytes = "Przeciąganie działa".toByteArray()
        write(files, base, "drag.txt", bytes)
        Preferences(context).apply { saveRoots(listOf(local)); savePlaces("tabs", listOf(base)); savePlaces("favorites", emptyList()) }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(device.wait(Until.hasObject(By.text("drag.txt")), 15000))
            val source = device.findObject(By.desc("Przytrzymaj, aby przeciągnąć drag.txt"))
            val bounds = device.findObject(By.text("Upuść tutaj")).visibleBounds
            source.drag(Point(bounds.centerX(), bounds.centerY()), 500)
            awaitCondition { files.list(target).isNotEmpty() }
            assertArrayEquals(bytes, files.input(files.list(target).single()).use { stream -> stream.readBytes() })
            assertTrue(files.list(base).any { file -> file.name == "drag.txt" })
        }
    }
    @Test fun tabletAndPhoneScreenshots() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val files = Files(context) { listOf(local) }
        val base = files.mkdir(Place(local.id), "Przestrzeń osobista")
        listOf("Dokumenty", "Fotografia", "Projekty", "Muzyka").forEach { files.mkdir(base, it) }
        write(files, base, "Plan podróży.txt", "Bieszczady 2026".toByteArray())
        val prefs = Preferences(context)
        prefs.autoUpdate = false
        prefs.saveRoots(listOf(local))
        prefs.savePlaces("tabs", listOf(base, base.child("Fotografia")))
        prefs.savePlaces("favorites", listOf(base.child("Dokumenty"), base.child("Fotografia")))
        device.executeShellCommand("wm size 1280x800")
        device.executeShellCommand("wm density 160")
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("Plan podróży.txt")), 15000))
        device.waitForIdle()
        device.findObject(By.desc("Właściwości Plan podróży.txt"))?.click()
        device.waitForIdle()
        val out = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        device.takeScreenshot(File(out, "tablet.png"))
        device.executeShellCommand("wm size 412x915")
        device.executeShellCommand("wm density 160")
        device.waitForIdle()
        assertTrue(device.wait(Until.hasObject(By.text("Plan podróży.txt")), 15000))
        device.takeScreenshot(File(out, "phone.png"))
        device.executeShellCommand("wm size reset")
        device.executeShellCommand("wm density reset")
    }
}
