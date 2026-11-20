package com.usquebox.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

internal enum class ConfigIssue {
    JSON, ACCOUNT, INBOUND, PORT, MTU, DNS, INITIAL_PACKET_SIZE, PMTU_TARGET, ENDPOINT,
    IP_FAMILY, CONGESTION, BRUTAL_RATE, NOISE, DURATION
}

internal class ConfigException(val issue: ConfigIssue) : IllegalArgumentException()

internal data class NoiseFields(
    val enabled: Boolean = false,
    val count: String = "0",
    val minSize: String = "0",
    val maxSize: String = "0",
    val delayMin: String = "0s",
    val delayMax: String = "0s"
)

internal data class ConfigFields(
    val ipv4: Boolean = true,
    val ipv6: Boolean = true,
    val mtu: String = "9000",
    val dns: String = "",
    val port: String = "443",
    val useIPv6: Boolean = false,
    val useHttp2: Boolean = false,
    val sni: String = "",
    val insecure: Boolean = false,
    val initialPacketSize: String = "0",
    val pmtuTarget: String = "",
    val endpointV4: String = "",
    val endpointV6: String = "",
    val endpointH2V4: String = "",
    val endpointH2V6: String = "",
    val keepalive: String = "30s",
    val reconnect: String = "1s",
    val congestion: String = "bbr",
    val bbrProfile: String = "standard",
    val brutalBps: String = "0",
    val noise: NoiseFields = NoiseFields(),
    val preNoise: NoiseFields = NoiseFields()
)

internal data class PreparedVpnConfig(
    val json: String,
    val ipv4Address: InetAddress?,
    val ipv6Address: InetAddress?,
    val dnsServers: List<InetAddress>,
    val mtu: Int,
    val useIPv6: Boolean,
    val useHttp2: Boolean
)

/** One parser for the editor and VPN. When saving, merge only the fields Android owns. */
internal class UsqueConfig private constructor(private val source: JSONObject, val fields: ConfigFields) {

    fun save(edits: ConfigFields): String = encode(edits, forVpn = false).json

    fun prepare(): PreparedVpnConfig = encode(fields, forVpn = true)

    private fun encode(edits: ConfigFields, forVpn: Boolean): PreparedVpnConfig {
        val root = JSONObject(source.toString())
        val account = root.getJSONObject("account")
        val tun = root.getJSONObject("inbound").getJSONObject("settings")
        val outbound = root.getJSONObject("outbound").getJSONObject("settings")

        val ipv4 = unicastLiteral(account.text("ipv4"))?.takeIf { edits.ipv4 && it is Inet4Address }
        val ipv6 = unicastLiteral(account.text("ipv6"))?.takeIf { edits.ipv6 && it !is Inet4Address }
        if (ipv4 == null && ipv6 == null) invalid(ConfigIssue.IP_FAMILY)
        val mtu = edits.mtu.number(1280..65535, ConfigIssue.MTU, auto = 9000)
        val enteredDns = edits.dns.split(dnsSeparators).filter { it.isNotBlank() }
        val configuredDns = enteredDns.ifEmpty { listOf("1.1.1.1", "2606:4700:4700::1111") }
        val servers = configuredDns.map { unicastLiteral(it) ?: invalid(ConfigIssue.DNS) }
            .filter { if (it is Inet4Address) ipv4 != null else ipv6 != null }
        // Keep user-entered DNS in JSON even if that family's toggle is off.
        // Only advertise servers reachable by the active VPN to Android.
        val activeDns = servers.ifEmpty {
            listOfNotNull(
                addressLiteral("1.1.1.1").takeIf { ipv4 != null },
                addressLiteral("2606:4700:4700::1111").takeIf { ipv6 != null }
            )
        }
        tun.put("ipv4", edits.ipv4)
        tun.put("ipv6", edits.ipv6)
        tun.put("mtu", mtu)
        tun.put("dns", JSONArray(enteredDns))

        val port = edits.port.number(1..65535, ConfigIssue.PORT, auto = 443)
        val initialSize = edits.initialPacketSize.number(1200..1452, ConfigIssue.INITIAL_PACKET_SIZE, auto = 0)
        val target = edits.pmtuTarget.trim()
        if (!edits.useHttp2 && target.isNotEmpty() && target != "off") {
            val addr = addressLiteral(target) ?: invalid(ConfigIssue.PMTU_TARGET)
            val active = if (addr is Inet4Address) ipv4 != null else ipv6 != null
            if (!active || addr.isAnyLocalAddress || addr.isMulticastAddress) invalid(ConfigIssue.PMTU_TARGET)
        }
        val endpointSettings = listOf(
            Triple("endpoint_v4", edits.endpointV4, true),
            Triple("endpoint_v6", edits.endpointV6, false),
            Triple("endpoint_h2_v4", edits.endpointH2V4, true),
            Triple("endpoint_h2_v6", edits.endpointH2V6, false)
        )
        for ((key, input, isV4) in endpointSettings) {
            val value = input.trim()
            if (value.isNotEmpty()) {
                val addr = addressLiteral(value) ?: invalid(ConfigIssue.ENDPOINT)
                if ((addr is Inet4Address) != isV4 || addr.isAnyLocalAddress || addr.isMulticastAddress) {
                    invalid(ConfigIssue.ENDPOINT)
                }
            }
            account.put(key, value)
        }
        val chosenEndpoint = when {
            edits.useHttp2 && edits.useIPv6 -> "endpoint_h2_v6"
            edits.useHttp2 -> "endpoint_h2_v4" // Go supplies its own default for this case.
            edits.useIPv6 -> "endpoint_v6"
            else -> "endpoint_v4"
        }
        if (chosenEndpoint != "endpoint_h2_v4" && account.text(chosenEndpoint).isBlank()) invalid(ConfigIssue.ENDPOINT)

        outbound.put("port", port)
        outbound.put("use_ipv6", edits.useIPv6)
        outbound.put("use_http2", edits.useHttp2)
        outbound.put("sni_address", edits.sni.trim()) // Clearing the field must clear the old SNI.
        outbound.put("insecure", edits.insecure)
        outbound.put("initial_packet_size", initialSize)
        outbound.put("pmtu_target", target)
        outbound.put("keepalive_period", duration(edits.keepalive, "30s").text)
        outbound.put("reconnect_delay", duration(edits.reconnect, "1s").text)

        val congestion = outbound.objectOrCreate("congestion")
        congestion.put("type", edits.congestion)
        congestion.remove("bbr_profile")
        congestion.remove("brutal_bps")
        when (edits.congestion) {
            "bbr" -> {
                if (edits.bbrProfile !in listOf("conservative", "standard", "aggressive")) invalid(ConfigIssue.CONGESTION)
                congestion.put("bbr_profile", edits.bbrProfile)
            }
            "brutal" -> {
                val bps = edits.brutalBps.trim().toLongOrNull()?.takeIf { it > 0 } ?: invalid(ConfigIssue.BRUTAL_RATE)
                congestion.put("brutal_bps", bps)
            }
            "reno" -> Unit
            else -> invalid(ConfigIssue.CONGESTION)
        }
        putNoise(outbound.objectOrCreate("noise"), edits.noise, fields.noise)
        putNoise(outbound.objectOrCreate("pre_noise"), edits.preNoise, fields.preNoise)

        // Align the runtime Go DNS list and available address families with the
        // VPN builder. Preserve the user's original selections in stored JSON.
        if (forVpn) {
            tun.put("ipv4", ipv4 != null)
            tun.put("ipv6", ipv6 != null)
            tun.put("dns", JSONArray(activeDns.mapNotNull { it.hostAddress }))
            if (edits.sni.isBlank()) outbound.put("sni_address", "consumer-masque.cloudflareclient.com")
        }
        return PreparedVpnConfig(root.toString(2), ipv4, ipv6, activeDns, mtu, edits.useIPv6, edits.useHttp2)
    }

    companion object {
        private val accountKeys = setOf(
            "private_key", "endpoint_pub_key", "endpoint_v4", "endpoint_v6", "endpoint_h2_v4",
            "endpoint_h2_v6", "license", "id", "access_token", "ipv4", "ipv6"
        )

        fun parse(json: String): UsqueConfig {
            val root = try {
                val input = JSONTokener(json)
                val document = input.nextValue() as? JSONObject ?: invalid(ConfigIssue.JSON)
                if (input.nextClean() != '\u0000') invalid(ConfigIssue.JSON)
                document
            } catch (_: JSONException) {
                invalid(ConfigIssue.JSON)
            }
            val legacy = !root.has("account") && root.has("private_key")
            if (legacy) {
                val account = JSONObject()
                for (key in accountKeys) if (root.has(key)) account.put(key, root.remove(key))
                root.put("account", account)
            }
            val account = root.optJSONObject("account") ?: invalid(ConfigIssue.ACCOUNT)
            if (account.text("private_key").isBlank() || account.text("endpoint_pub_key").isBlank()) invalid(ConfigIssue.ACCOUNT)

            val inbound = root.objectOrCreate("inbound")
            if (inbound.text("type").ifEmpty { "tun" } != "tun") invalid(ConfigIssue.INBOUND)
            inbound.put("type", "tun")
            val tun = inbound.objectOrCreate("settings")
            val outbound = root.objectOrCreate("outbound")
            if (!outbound.has("tag")) outbound.put("tag", "warp")
            val ob = outbound.objectOrCreate("settings")
            val cc = ob.objectOrCreate("congestion")
            val noise = ob.objectOrCreate("noise")
            val preNoise = ob.objectOrCreate("pre_noise")
            val dns = tun.optJSONArray("dns")
            if (!tun.isNull("dns") && dns == null) invalid(ConfigIssue.DNS)

            return UsqueConfig(root, ConfigFields(
                ipv4 = tun.flag("ipv4", true),
                ipv6 = tun.flag("ipv6", true),
                mtu = tun.numberText("mtu", "9000", ConfigIssue.MTU).let { if (it == "0") "9000" else it },
                dns = if (dns == null) "" else (0 until dns.length()).joinToString(", ") {
                    dns.opt(it) as? String ?: invalid(ConfigIssue.DNS)
                },
                port = ob.numberText("port", "443", ConfigIssue.PORT).let { if (it == "0") "443" else it },
                useIPv6 = ob.flag("use_ipv6", false),
                useHttp2 = ob.flag("use_http2", false),
                sni = ob.text("sni_address"),
                insecure = ob.flag("insecure", false),
                initialPacketSize = ob.numberText("initial_packet_size", "0", ConfigIssue.INITIAL_PACKET_SIZE),
                pmtuTarget = ob.text("pmtu_target"),
                endpointV4 = account.text("endpoint_v4"),
                endpointV6 = account.text("endpoint_v6"),
                endpointH2V4 = account.text("endpoint_h2_v4"),
                endpointH2V6 = account.text("endpoint_h2_v6"),
                keepalive = ob.text("keepalive_period").ifEmpty { "30s" },
                reconnect = ob.text("reconnect_delay").ifEmpty { "1s" },
                congestion = cc.text("type").ifEmpty { if (legacy) "bbr" else "reno" },
                bbrProfile = cc.text("bbr_profile").ifEmpty { "standard" },
                brutalBps = cc.numberText("brutal_bps", "0", ConfigIssue.BRUTAL_RATE),
                noise = readNoise(noise, if (legacy) NoiseFields(true, "5", "100", "400", "10ms", "50ms") else NoiseFields()),
                preNoise = readNoise(preNoise, if (legacy) NoiseFields(true, "3", "64", "128", "5ms", "15ms") else NoiseFields())
            ))
        }
    }
}

private fun invalid(issue: ConfigIssue): Nothing = throw ConfigException(issue)

private val dnsSeparators = Regex("[\\s,]+")

private fun JSONObject.objectOrCreate(key: String): JSONObject = optJSONObject(key)
    ?: if (isNull(key)) JSONObject().also { put(key, it) } else invalid(ConfigIssue.JSON)

private fun JSONObject.text(key: String): String = if (isNull(key)) "" else opt(key) as? String ?: invalid(ConfigIssue.JSON)

private fun JSONObject.flag(key: String, default: Boolean): Boolean =
    if (isNull(key)) default else opt(key) as? Boolean ?: invalid(ConfigIssue.JSON)

private fun JSONObject.numberText(key: String, default: String, issue: ConfigIssue): String =
    if (isNull(key)) default else (opt(key) as? Number)?.toString() ?: invalid(issue)

private fun String.number(range: IntRange, issue: ConfigIssue, auto: Int? = null): Int {
    val raw = trim()
    if (auto != null && (raw.isEmpty() || raw == "0")) return auto
    return raw.toIntOrNull()?.takeIf { it in range } ?: invalid(issue)
}

private val durationPart = Regex("([0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(ns|us|µs|μs|ms|s|m|h)")
private val unitsInNs = mapOf(
    "ns" to 1L, "us" to 1000L, "µs" to 1000L, "μs" to 1000L, "ms" to 1000000L,
    "s" to 1000000000L, "m" to 60000000000L, "h" to 3600000000000L
)
private data class GoDuration(val text: String, val ns: BigDecimal)

private fun duration(input: String, default: String): GoDuration {
    val text = input.trim().ifEmpty { default }
    if (text == "0" || text == "+0") return GoDuration(text, BigDecimal.ZERO)
    val unsigned = text.removePrefix("+")
    var end = 0
    var ns = BigDecimal.ZERO
    for (part in durationPart.findAll(unsigned)) {
        if (part.range.first != end) invalid(ConfigIssue.DURATION)
        end = part.range.last + 1
        val amount = BigDecimal(part.groupValues[1]) * BigDecimal.valueOf(unitsInNs.getValue(part.groupValues[2]))
        ns += amount.setScale(0, RoundingMode.DOWN)
        if (ns > BigDecimal.valueOf(Long.MAX_VALUE)) invalid(ConfigIssue.DURATION)
    }
    if (end == 0 || end != unsigned.length) invalid(ConfigIssue.DURATION)
    return GoDuration(text, ns)
}

private fun readNoise(json: JSONObject, default: NoiseFields): NoiseFields = NoiseFields(
    enabled = json.flag("enabled", default.enabled),
    count = json.numberText("count", default.count, ConfigIssue.NOISE),
    minSize = json.numberText("min_size", default.minSize, ConfigIssue.NOISE),
    maxSize = json.numberText("max_size", default.maxSize, ConfigIssue.NOISE),
    delayMin = json.text("delay_min").ifEmpty { default.delayMin },
    delayMax = json.text("delay_max").ifEmpty { default.delayMax }
)

private fun putNoise(json: JSONObject, fields: NoiseFields, previous: NoiseFields) {
    json.put("enabled", fields.enabled)
    try {
        putNoiseValues(json, fields)
    } catch (e: ConfigException) {
        if (fields.enabled) throw e
        // Disabled noise must not block saving because of invalid hidden
        // draft values. Keep the imported values (including legacy defaults).
        json.put("count", previous.count.toIntOrNull() ?: 0)
        json.put("min_size", previous.minSize.toIntOrNull() ?: 0)
        json.put("max_size", previous.maxSize.toIntOrNull() ?: 0)
        json.put("delay_min", previous.delayMin)
        json.put("delay_max", previous.delayMax)
    }
}

private fun putNoiseValues(json: JSONObject, fields: NoiseFields) {
    val count = fields.count.number(0..Int.MAX_VALUE, ConfigIssue.NOISE)
    val minSize = fields.minSize.number(0..65507, ConfigIssue.NOISE)
    val maxSize = fields.maxSize.number(0..65507, ConfigIssue.NOISE)
    val minDelay = duration(fields.delayMin, "0s")
    val maxDelay = duration(fields.delayMax, "0s")
    if (minSize > maxSize || minDelay.ns > maxDelay.ns) invalid(ConfigIssue.NOISE)
    json.put("count", count)
    json.put("min_size", minSize)
    json.put("max_size", maxSize)
    json.put("delay_min", minDelay.text)
    json.put("delay_max", maxDelay.text)
}

/** Only numeric literal syntax reaches InetAddress, so importing a config never queries DNS. */
private fun addressLiteral(value: String): InetAddress? {
    if (':' !in value) {
        val parts = value.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { part ->
            if (part.isEmpty() || part.any { it !in '0'..'9' } || part.length > 1 && part.startsWith('0')) return null
            part.toIntOrNull()?.takeIf { it in 0..255 }?.toByte() ?: return null
        }
        return InetAddress.getByAddress(octets.toByteArray())
    }
    if (value.any { it !in "0123456789abcdefABCDEF:." }) return null
    return try {
        InetAddress.getByName(value)
    } catch (_: UnknownHostException) {
        null
    }
}

private fun unicastLiteral(value: String): InetAddress? = addressLiteral(value)?.takeUnless {
    it.isAnyLocalAddress || it.isLoopbackAddress || it.isMulticastAddress
}
