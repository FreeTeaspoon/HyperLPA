package app.hyperlpa.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NekokoCloudServiceTest {
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
}
