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
    @Test fun semanticVersionsAreNumericAndStableOnly() {
        assertTrue(Updates.newer("0.10.0", "0.9.9"))
        assertFalse(Updates.newer("0.1.0", "0.1.0"))
        assertFalse(Updates.newer("0.0.9", "0.1.0"))
        assertFalse(Updates.newer("1.0.0-rc1", "0.1.0"))
        assertFalse(Updates.newer("invalid", "0.1.0"))
    }
}
