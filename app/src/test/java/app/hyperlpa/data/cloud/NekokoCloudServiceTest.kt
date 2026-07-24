package app.hyperlpa.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NekokoCloudServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `memory icon cache evicts by bytes and entry count`() {
        val cache = ByteArrayLruCache(maxBytes = 6, maxEntries = 2)
        cache.put("a", ByteArray(3))
        cache.put("b", ByteArray(3))
        assertNotNull(cache["a"])
        cache.put("c", ByteArray(3))

        // Reading a made it most-recently used, so b is the eldest entry.
        assertNull(cache["b"])
        assertNotNull(cache["a"])
        assertNotNull(cache["c"])
        assertEquals(2, cache.size)
        assertEquals(6L, cache.byteSize)

        cache.put("oversized", ByteArray(7))
        assertNull(cache["oversized"])
        assertEquals(6L, cache.byteSize)
    }

    @Test
    fun `decodes three digit mnc from profile owner`() {
        assertEquals(
            MobileNetworkCode(mcc = "310", mnc = "260"),
            decodeMccMnc("130062"),
        )
    }

    @Test
    fun `decodes two digit mnc from profile owner`() {
        assertEquals(
            MobileNetworkCode(mcc = "310", mnc = "26"),
            decodeMccMnc("13F062"),
        )
        assertEquals(
            MobileNetworkCode(mcc = "505", mnc = "02"),
            decodeMccMnc("05F520"),
        )
        assertNull(decodeMccMnc("not-hex"))
    }

    @Test
    fun `operator catalog prefers matching gid entry`() {
        val catalog = OperatorCatalog.parse(
            """
            [[operators]]
            mnc = "260"
            operator = "Generic"
            icon = "generic"
            icon_scope = "us"
            [[operators.gids]]
            gid1 = "ab"
            profile_provider_names = ["Example Mobile"]
            icon = "example"
            icon_scope = "mvno"
            """.trimIndent(),
        )

        assertEquals(
            OperatorIconReference(name = "example", scope = "mvno"),
            catalog.resolve(
                mnc = "260",
                gid1 = "AB",
                gid2 = null,
                profileName = "Travel",
                providerName = "Example Mobile",
            ),
        )
    }

    @Test
    fun `operator catalog rejects oversized or control-character artwork fields`() {
        val oversized = "x".repeat(257)
        val catalog = OperatorCatalog.parse(
            """
            [[operators]]
            mnc = "260"
            icon = "$oversized"
            icon_scope = "scope\ncontrol"
            """.trimIndent(),
        )

        assertNull(
            catalog.resolve(
                mnc = "260",
                gid1 = null,
                gid2 = null,
                profileName = null,
                providerName = null,
            ),
        )
    }

    @Test
    fun `size prediction uses strongest metadata and eum match`() {
        val catalog = ProfileSizeCatalog.parse(
            """
            {
              "reference_eum": "89044045",
              "results": [
                {
                  "plmn": "310260",
                  "serviceProviderName": "Generic",
                  "rsp": "rsp.example",
                  "reference_size": 41000,
                  "eum_sizes": {"89044045": 42000}
                },
                {
                  "plmn": "310260",
                  "serviceProviderName": "Example Mobile",
                  "rsp": "rsp.example",
                  "reference_size": 51000,
                  "eum_sizes": {"89044045": 52000}
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(
            52_000L,
            catalog?.predict(
                eid = "89044045123456789012345678901234",
                smdpAddress = "RSP.EXAMPLE",
                plmn = "310260",
                providerName = "Example Mobile",
            ),
        )
    }

    @Test
    fun `exact provider does not match a shorter generic substring`() {
        val catalog = ProfileSizeCatalog.parse(
            """
            {
              "reference_eum": "89044045",
              "results": [
                {
                  "plmn": "90101",
                  "serviceProviderName": "SIM",
                  "rsp": "generic.example",
                  "reference_size": 28000,
                  "eum_sizes": {"89086030": 28500}
                },
                {
                  "plmn": "50502",
                  "serviceProviderName": "Amaysim",
                  "rsp": "amaysim.example",
                  "reference_size": 34000,
                  "eum_sizes": {"89086030": 47843}
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(
            47_843L,
            catalog?.predict(
                eid = "890860302022000000025000022593568",
                smdpAddress = null,
                plmn = null,
                providerName = "Amaysim",
            ),
        )
    }

    @Test
    fun `bounded cache reader rejects oversized and missing files`() {
        val cache = temporaryFolder.newFile("catalog.toml")
        cache.writeBytes(ByteArray(33) { 'A'.code.toByte() })

        assertNull(readUtf8FileBounded(cache, 32))
        assertEquals("A".repeat(33), readUtf8FileBounded(cache, 33))
        assertNull(readUtf8FileBounded(temporaryFolder.root.resolve("missing"), 32))
    }

    @Test
    fun `disk cache pruning evicts oldest files by count and total bytes`() {
        val root = temporaryFolder.newFolder("bounded-cache")
        val oldest = root.resolve("oldest").apply {
            writeBytes(ByteArray(4))
            setLastModified(1L)
        }
        val middle = root.resolve("middle").apply {
            writeBytes(ByteArray(4))
            setLastModified(2L)
        }
        val newest = root.resolve("newest").apply {
            writeBytes(ByteArray(4))
            setLastModified(3L)
        }

        pruneCacheDirectory(root, maxBytes = 8, maxFiles = 2)

        assertEquals(false, oldest.exists())
        assertEquals(true, middle.exists())
        assertEquals(true, newest.exists())
    }
}
