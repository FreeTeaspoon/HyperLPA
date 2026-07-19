package app.hyperlpa.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileMetadataStoreTest {
    @Test
    fun tagsAreTrimmedAndDeduplicatedIgnoringCase() {
        val tags = normalizeProfileTags(listOf(" Travel ", "travel", "Work", ""))

        assertEquals(linkedSetOf("Travel", "Work"), tags)
    }

    @Test
    fun tagsRespectStorageLimits() {
        val tags = normalizeProfileTags((1..20).map { "tag-$it-${"x".repeat(40)}" })

        assertEquals(16, tags.size)
        assertEquals(true, tags.all { it.length <= 32 })
    }
}
