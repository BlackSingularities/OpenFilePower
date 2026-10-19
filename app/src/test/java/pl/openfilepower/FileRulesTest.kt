package pl.openfilepower
import org.junit.Assert.*
import org.junit.Test

class FileRulesTest {
    @Test fun rejectsTraversalAndInvalidNames() {
        listOf("", "..", ".", "a/b", "a\\b", " x", "a:b", "a\u0000b").forEach { assertTrue(it, runCatching { FileRules.validateName(it) }.isFailure) }
        listOf("Zdjęcia", "photo.jpg", "wakacje 2026").forEach(FileRules::validateName)
    }
    @Test fun detectsSelfAndDescendantWithoutPrefixCollision() {
        val folder = Place("nas", listOf("photos"))
        assertTrue(FileRules.isWithin(folder, folder))
        assertTrue(FileRules.isWithin(folder, folder.child("2026")))
        assertFalse(FileRules.isWithin(folder, Place("nas", listOf("photos-backup"))))
        assertFalse(FileRules.isWithin(folder, Place("usb", listOf("photos"))))
    }
    @Test fun versionsAreNumericIncludingCiBuildNumbers() {
        assertTrue(Updates.newer("0.10.0", "0.9.9"))
        assertFalse(Updates.newer("0.1.0", "0.1.0"))
        assertFalse(Updates.newer("0.0.9", "0.1.0"))
        assertFalse(Updates.newer("1.0.0-rc1", "0.1.0"))
        assertFalse(Updates.newer("invalid", "0.1.0"))
        assertTrue(Updates.newer("0.2.0.41", "0.2.0"))
        assertTrue(Updates.newer("0.2.0.41", "0.2.0.40"))
        assertFalse(Updates.newer("0.2.0", "0.2.0.40"))
        assertTrue(Updates.newer("0.2.1", "0.2.0.99"))
        assertFalse(Updates.newer("0.2.0", "0.2.0-debug"))
    }
    @Test fun numberedNamesKeepExtension() {
        assertEquals("zdjęcie (2).jpg", FileRules.numbered("zdjęcie.jpg", 2, false))
        assertEquals("archiwum.tar (3).gz", FileRules.numbered("archiwum.tar.gz", 3, false))
        assertEquals("Projekt.v2 (2)", FileRules.numbered("Projekt.v2", 2, true))
        assertEquals(".bashrc (2)", FileRules.numbered(".bashrc", 2, false))
    }
    @Test fun sanitizesArchiveNames() {
        assertEquals("a_b", FileRules.sanitize("a:b"))
        assertEquals("_", FileRules.sanitize(".."))
        assertEquals("Raport_2026_.pdf", FileRules.sanitize("Raport?2026*.pdf"))
    }
    @Test fun naturalOrderComparesNumbersByValue() {
        val sorted = listOf("plik 10", "Plik 2", "plik 1", "plik 02a", "a").sortedWith { a, b -> naturalCompare(a, b) }
        assertEquals(listOf("a", "plik 1", "Plik 2", "plik 02a", "plik 10"), sorted)
    }
}
