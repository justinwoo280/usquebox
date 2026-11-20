package com.usquebox.data

import java.net.Inet4Address
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class UsqueConfigTest {
    private val account = """
        {
          "private_key": "test-not-a-private-key",
          "endpoint_pub_key": "test-not-a-public-key",
          "endpoint_v4": "192.0.2.1",
          "endpoint_v6": "2001:db8::1",
          "ipv4": "172.16.0.2",
          "ipv6": "2001:db8::2",
          "future_account_field": true
        }
    """.trimIndent()

    private val minimal = """
        {"account": $account, "inbound": {"type": "tun", "settings": {}}, "outbound": {"settings": {}}}
    """.trimIndent()

    @Test
    fun fullConfigRoundTripKeepsAndroidEditsAndUnknownFields() {
        val source = """
            {
              "account": $account,
              "future_root": {"val": 17},
              "inbound": {"type": "tun", "settings": {"mtu": 1380, "ipv4": false, "dns": ["2606:4700:4700::1111"], "auto_route": false}},
              "outbound": {"tag": "test", "settings": {
                  "port": 8443, "use_ipv6": true, "sni_address": "sni.example", "initial_packet_size": 1230,
                  "pmtu_target": "2001:db8::3", "insecure": true, "on_connect": "desktop-hook",
                  "congestion": {"type": "bbr", "bbr_profile": "aggressive", "future_cc": 8},
                  "noise": {"enabled": true, "count": 5, "min_size": 101, "max_size": 401, "delay_min": "10ms", "delay_max": "40ms", "future_noise": false},
                  "pre_noise": {"enabled": false, "count": 9}
              }}
            }
        """.trimIndent()
        val doc = UsqueConfig.parse(source)
        assertEquals("1380", doc.fields.mtu)
        assertEquals("2606:4700:4700::1111", doc.fields.dns)
        assertEquals("1230", doc.fields.initialPacketSize)
        assertEquals("2001:db8::3", doc.fields.pmtuTarget)
        assertTrue(doc.fields.insecure)
        assertFalse(doc.fields.ipv4)
        assertTrue(doc.fields.ipv6)
        assertEquals("9", doc.fields.preNoise.count)
        assertEquals("101", doc.fields.noise.minSize)

        val edits = doc.fields.copy(
            mtu = "9000", endpointV6 = "2001:db8::8", port = "2408", sni = "", insecure = false,
            initialPacketSize = "0", pmtuTarget = "off", congestion = "brutal", brutalBps = "12345678",
            keepalive = "1m30s", reconnect = "750ms"
        )
        val saved = doc.save(edits)
        val parsed = UsqueConfig.parse(saved)
        assertEquals(edits.copy(bbrProfile = "standard"), parsed.fields)
        val full = JSONObject(saved)
        val accountAfter = full.getJSONObject("account")
        assertEquals("test-not-a-private-key", accountAfter.getString("private_key"))
        assertTrue(accountAfter.getBoolean("future_account_field"))
        assertEquals("2001:db8::8", accountAfter.getString("endpoint_v6"))
        assertEquals(17, full.getJSONObject("future_root").getInt("val"))
        assertFalse(full.getJSONObject("inbound").getJSONObject("settings").getBoolean("auto_route"))
        val ob = full.getJSONObject("outbound").getJSONObject("settings")
        assertEquals("desktop-hook", ob.getString("on_connect"))
        assertEquals("", ob.getString("sni_address"))
        assertFalse(ob.getJSONObject("noise").getBoolean("future_noise"))
        val cc = ob.getJSONObject("congestion")
        assertFalse(cc.has("bbr_profile"))
        assertEquals(12345678L, cc.getLong("brutal_bps"))
        assertEquals(8, cc.getInt("future_cc"))
    }

    @Test
    fun legacyFlatAccountMigratesWithoutLosingEdits() {
        val doc = UsqueConfig.parse(account)
        assertEquals("3", doc.fields.preNoise.count)
        assertEquals("64", doc.fields.preNoise.minSize)
        assertTrue(doc.fields.preNoise.enabled)
        assertEquals("5", doc.fields.noise.count)
        val saved = doc.save(doc.fields.copy(mtu = "1500", useIPv6 = true, ipv4 = false))
        val root = JSONObject(saved)
        assertFalse(root.has("private_key"))
        assertEquals("test-not-a-private-key", root.getJSONObject("account").getString("private_key"))
        assertTrue(root.getBoolean("future_account_field"))
        val vpn = UsqueConfig.parse(saved).prepare()
        assertEquals(1500, vpn.mtu)
        assertTrue(vpn.useIPv6)
        assertNull(vpn.ipv4Address)
        assertNotNull(vpn.ipv6Address)
        assertEquals(1, vpn.dnsServers.size)
    }

    @Test
    fun vpnUsesSameMtuAndActiveDnsAsGoWhileSavedConfigRetainsBothFamilies() {
        val doc = UsqueConfig.parse(minimal)
        val saved = doc.save(doc.fields.copy(ipv6 = false, mtu = "0", dns = "2606:4700:4700::1111, 8.8.4.4\n1.0.0.1"))
        val parsed = UsqueConfig.parse(saved)
        val vpn = parsed.prepare()
        assertEquals(9000, vpn.mtu)
        assertTrue(vpn.ipv4Address is Inet4Address)
        assertNull(vpn.ipv6Address)
        assertEquals(listOf("8.8.4.4", "1.0.0.1"), vpn.dnsServers.map { it.hostAddress })
        val runtime = JSONObject(vpn.json).getJSONObject("inbound").getJSONObject("settings")
        assertEquals(vpn.mtu, runtime.getInt("mtu"))
        assertEquals(2, runtime.getJSONArray("dns").length())
        assertFalse(runtime.getBoolean("ipv6"))
        val stored = JSONObject(saved).getJSONObject("inbound").getJSONObject("settings")
        assertEquals(3, stored.getJSONArray("dns").length())
        assertEquals("2606:4700:4700::1111, 8.8.4.4, 1.0.0.1", parsed.fields.dns)
    }

    @Test
    fun missingNoiseBlocksStayDisabledAndDisabledDraftCanBeSaved() {
        val doc = UsqueConfig.parse(minimal)
        assertFalse(doc.fields.noise.enabled)
        assertFalse(doc.fields.preNoise.enabled)
        val edited = doc.fields.copy(preNoise = doc.fields.preNoise.copy(enabled = false, count = "9", maxSize = "128"))
        val saved = UsqueConfig.parse(doc.save(edited))
        assertFalse(saved.fields.preNoise.enabled)
        assertEquals("9", saved.fields.preNoise.count)
        val invalidHidden = saved.fields.copy(preNoise = saved.fields.preNoise.copy(count = ""))
        assertEquals("9", UsqueConfig.parse(saved.save(invalidHidden)).fields.preNoise.count)
    }

    @Test
    fun invalidSettingsDoNotSilentlyFallBack() {
        val doc = UsqueConfig.parse(minimal)
        val fields = doc.fields
        for ((issue, draft) in listOf(
            ConfigIssue.MTU to fields.copy(mtu = "1279"),
            ConfigIssue.DNS to fields.copy(dns = "resolver.example"),
            ConfigIssue.PORT to fields.copy(port = "65536"),
            ConfigIssue.INITIAL_PACKET_SIZE to fields.copy(initialPacketSize = "1453"),
            ConfigIssue.ENDPOINT to fields.copy(useIPv6 = true, useHttp2 = true),
            ConfigIssue.PMTU_TARGET to fields.copy(ipv4 = false, pmtuTarget = "1.1.1.1"),
            ConfigIssue.IP_FAMILY to fields.copy(ipv4 = false, ipv6 = false),
            ConfigIssue.BRUTAL_RATE to fields.copy(congestion = "brutal", brutalBps = "0"),
            ConfigIssue.DURATION to fields.copy(reconnect = "9999999999999h"),
            ConfigIssue.NOISE to fields.copy(noise = NoiseFields(enabled = true, delayMin = "2s", delayMax = "999ms"))
        )) {
            assertEquals(issue, assertThrows(ConfigException::class.java) { doc.save(draft) }.issue)
        }
    }

    @Test
    fun rejectInvalidJsonMissingKeysAndDesktopInbound() {
        assertEquals(ConfigIssue.JSON, assertThrows(ConfigException::class.java) { UsqueConfig.parse("$minimal garbage") }.issue)
        assertEquals(ConfigIssue.ACCOUNT, assertThrows(ConfigException::class.java) { UsqueConfig.parse("{}") }.issue)
        val socks = minimal.replace("\"tun\"", "\"socks\"")
        assertEquals(ConfigIssue.INBOUND, assertThrows(ConfigException::class.java) { UsqueConfig.parse(socks) }.issue)
    }
}
