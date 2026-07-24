package app.hyperlpa.ui

import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderIconLookupTest {
    @Test
    fun lookupIsStableInTurkishLocale() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))

            assertTrue(hasProviderIcon("DIGI", mapOf("digi" to "file:///icon.img")))
        } finally {
            Locale.setDefault(previousLocale)
        }
    }
}
