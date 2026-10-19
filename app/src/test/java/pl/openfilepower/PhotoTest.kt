package pl.openfilepower

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class PhotoTest {
    @Test fun recognizesCameraRawFormats() {
        listOf("DSC0001.ARW", "a.nef", "IMG_1.CR3", "x.cr2", "f.raf", "d.dng", "p.rw2", "o.orf", "k.pef").forEach { assertTrue(it, RawFormats.isRaw(it)) }
        listOf("a.jpg", "b.heic", "c.png", "raw", "notes.txt").forEach { assertFalse(it, RawFormats.isRaw(it)) }
    }
    @Test fun formatsShutterSpeed() {
        assertEquals("1/250 s", PhotoExif.shutter(0.004))
        assertEquals("1/8000 s", PhotoExif.shutter(1.0 / 8000))
        assertEquals("2 s", PhotoExif.shutter(2.0))
        assertEquals("1.3 s", PhotoExif.shutter(1.3))
    }
    /** Osadzony JPEG wewnątrz „RAW-a”: koniec strumienia po FFD9, z pominięciem FF00 i znaczników restartu w danych. */
    @Test fun findsEmbeddedJpegEnd() {
        val header = byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x11, 0x22)
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),                                   // SOI
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x04, 0x4A, 0x46,           // APP0, długość 4
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x03, 0x01,                 // SOS, długość 3
            0x12, 0xFF.toByte(), 0x00, 0x34, 0xFF.toByte(), 0xD3.toByte(), 0x56, // dane z FF00 i RST3
            0xFF.toByte(), 0xD9.toByte(),                                   // EOI
        )
        val trailer = byteArrayOf(0x00, 0x01, 0x02)
        val buffer = ByteBuffer.wrap(header + jpeg + trailer)
        assertEquals(header.size + jpeg.size, RawPreview.jpegEnd(buffer, header.size))
        assertEquals(-1, RawPreview.jpegEnd(ByteBuffer.wrap(header + jpeg.copyOf(10)), header.size))
    }
}
