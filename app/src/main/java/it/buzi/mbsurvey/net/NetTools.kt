package it.buzi.mbsurvey.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import it.buzi.mbsurvey.model.Channels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit

/** Ping, traceroute, subnet scan, port scan, DNS, throughput. ICMP uses the system ping binary (no root). */
object NetTools {

    class PingResult(val host: String, val ip: String?, val sent: Int, val received: Int, val minMs: Double?, val avgMs: Double?, val maxMs: Double?, val raw: String) {
        val lossPct: Int get() = if (sent == 0) 100 else ((sent - received) * 100 / sent)
    }

    class Hop(val ttl: Int, val ip: String?, val host: String?, val rttMs: Double?)

    class HostInfo(val ip: String, val hostname: String?, val mac: String?, val vendor: String?, val openPorts: List<Int>, val rttMs: Double?)

    class ConnectionInfo(
        val ssid: String?, val bssid: String?, val rssi: Int?, val linkMbps: Int?, val txMbps: Int?, val rxMbps: Int?,
        val freqMhz: Int?, val channel: Int?, val band: String?, val standard: String?,
        val ip: String?, val prefix: Int?, val gateway: String?, val dns: List<String>, val mtu: Int?,
        val hasInternet: Boolean, val externalIp: String?
    )

    // ---------------- ping ----------------

    private fun pingBinary(): String = listOf("/system/bin/ping", "/bin/ping").firstOrNull { File(it).exists() } ?: "ping"

    suspend fun ping(host: String, count: Int = 4, timeoutSec: Int = 2, ttl: Int? = null, intervalSec: Double = 0.5): PingResult = withContext(Dispatchers.IO) {
        val cmd = mutableListOf(pingBinary(), "-c", count.toString(), "-W", timeoutSec.toString(), "-i", intervalSec.toString(), "-n")
        if (ttl != null) { cmd.add("-t"); cmd.add(ttl.toString()) }
        cmd.add(host)
        val out = runProcess(cmd, (count * (timeoutSec + intervalSec) + 3).toLong())
        parsePing(host, out)
    }

    private fun parsePing(host: String, out: String): PingResult {
        val ip = Regex("""PING [^ ]+ \(([\d.]+)\)""").find(out)?.groupValues?.get(1)
        val stats = Regex("""(\d+) packets transmitted, (\d+) (?:packets )?received""").find(out)
        val sent = stats?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val recv = stats?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val rtt = Regex("""= ([\d.]+)/([\d.]+)/([\d.]+)""").find(out)
        return PingResult(host, ip, sent, recv, rtt?.groupValues?.get(1)?.toDoubleOrNull(), rtt?.groupValues?.get(2)?.toDoubleOrNull(), rtt?.groupValues?.get(3)?.toDoubleOrNull(), out)
    }

    /** Single-probe ping used by the active survey: returns rtt in ms or null when lost. */
    suspend fun quickPing(host: String, timeoutSec: Int = 1): Double? {
        val r = ping(host, count = 1, timeoutSec = timeoutSec, intervalSec = 0.2)
        return if (r.received > 0) r.avgMs else null
    }

    /** Several probes for latency/jitter/loss. */
    suspend fun probe(host: String, count: Int, timeoutSec: Int = 1): PingResult = ping(host, count, timeoutSec, null, 0.2)

    // ---------------- traceroute (ping with increasing TTL) ----------------

    suspend fun traceroute(host: String, maxHops: Int = 30, onHop: suspend (Hop) -> Unit) = withContext(Dispatchers.IO) {
        val target = runCatching { InetAddress.getByName(host).hostAddress }.getOrNull()
        for (ttl in 1..maxHops) {
            if (!isActive) break
            val cmd = listOf(pingBinary(), "-c", "1", "-W", "2", "-t", ttl.toString(), "-n", host)
            val t0 = System.nanoTime()
            val out = runProcess(cmd, 4)
            val elapsed = (System.nanoTime() - t0) / 1e6
            val exceeded = Regex("""From ([\d.]+)""").find(out)?.groupValues?.get(1)
            val reached = Regex("""bytes from ([\d.]+)""").find(out)?.groupValues?.get(1)
            val rtt = Regex("""time=([\d.]+)""").find(out)?.groupValues?.get(1)?.toDoubleOrNull()
            val ip = reached ?: exceeded
            val name = ip?.let { runCatching { withTimeoutOrNull(800) { InetAddress.getByName(it).canonicalHostName } }.getOrNull() }?.takeIf { it != ip }
            onHop(Hop(ttl, ip, name, rtt ?: if (ip != null) elapsed else null))
            if (reached != null || (ip != null && ip == target)) break
        }
    }

    // ---------------- subnet scan ----------------

    val commonPorts = listOf(22, 23, 53, 80, 135, 139, 443, 445, 161, 515, 631, 3389, 5900, 8080, 8443, 9100)

    /** Hosts in a CIDR like 192.168.1.0/24 (max /16 = 65k; UI should warn above /22). */
    fun hostsOf(cidr: String): List<String> {
        val (ipStr, prefStr) = cidr.trim().split("/").let { if (it.size == 2) it else listOf(it[0], "24") }
        val prefix = prefStr.toIntOrNull()?.coerceIn(8, 32) ?: 24
        val ip = ipToLong(ipStr) ?: return emptyList()
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val net = ip and mask
        val size = 1L shl (32 - prefix)
        val list = ArrayList<String>()
        val start = if (prefix >= 31) 0L else 1L
        val end = if (prefix >= 31) size - 1 else size - 2
        for (i in start..end) list.add(longToIp(net + i))
        return list
    }

    private fun ipToLong(ip: String): Long? {
        val p = ip.trim().split(".")
        if (p.size != 4) return null
        var v = 0L
        for (s in p) { val n = s.toIntOrNull() ?: return null; if (n !in 0..255) return null; v = (v shl 8) or n.toLong() }
        return v
    }

    private fun longToIp(v: Long): String = "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"

    /**
     * Scans a subnet: ICMP ping + TCP probes on common ports in parallel, then ARP/neighbour table for MACs.
     * onHost is called for each live host as soon as it is found.
     */
    suspend fun scanSubnet(cidr: String, ports: List<Int> = listOf(80, 443, 22, 445), onProgress: suspend (Int, Int) -> Unit, onHost: suspend (HostInfo) -> Unit) = coroutineScope {
        val hosts = hostsOf(cidr)
        val sem = Semaphore(48)
        var done = 0
        val jobs = hosts.map { ip ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    if (!isActive) return@withPermit
                    val rtt = quickPingFast(ip)
                    val open = ArrayList<Int>()
                    for (p in ports) if (tcpOpen(ip, p, 300)) open.add(p)
                    done++
                    if (done % 8 == 0 || done == hosts.size) onProgress(done, hosts.size)
                    if (rtt != null || open.isNotEmpty()) {
                        val name = runCatching { withTimeoutOrNull(600) { InetAddress.getByName(ip).canonicalHostName } }.getOrNull()?.takeIf { it != ip }
                        onHost(HostInfo(ip, name, null, null, open, rtt))
                    }
                }
            }
        }
        jobs.awaitAll()
    }

    private suspend fun quickPingFast(ip: String): Double? = withContext(Dispatchers.IO) {
        val out = runProcess(listOf(pingBinary(), "-c", "1", "-W", "1", "-n", ip), 3)
        Regex("""time=([\d.]+)""").find(out)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    fun tcpOpen(ip: String, port: Int, timeoutMs: Int): Boolean = runCatching {
        Socket().use { s -> s.connect(InetSocketAddress(ip, port), timeoutMs); true }
    }.getOrDefault(false)

    /** MAC addresses from the neighbour table (works without root on most devices; may be empty on Android 11+). */
    fun neighbourMacs(): Map<String, String> {
        val map = HashMap<String, String>()
        runCatching {
            val out = runProcessBlocking(listOf("ip", "neigh", "show"), 3)
            for (line in out.lines()) {
                val parts = line.trim().split(Regex("\\s+"))
                val li = parts.indexOf("lladdr")
                if (parts.isNotEmpty() && li > 0 && li + 1 < parts.size && !line.contains("FAILED")) map[parts[0]] = parts[li + 1].uppercase()
            }
        }
        if (map.isEmpty()) runCatching {
            File("/proc/net/arp").readLines().drop(1).forEach { line ->
                val p = line.trim().split(Regex("\\s+"))
                if (p.size >= 4 && p[3] != "00:00:00:00:00:00") map[p[0]] = p[3].uppercase()
            }
        }
        return map
    }

    // ---------------- port scan ----------------

    suspend fun scanPorts(ip: String, ports: List<Int>, timeoutMs: Int = 400, onOpen: suspend (Int) -> Unit, onProgress: suspend (Int, Int) -> Unit) = coroutineScope {
        val sem = Semaphore(64)
        var done = 0
        ports.map { p ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    if (tcpOpen(ip, p, timeoutMs)) onOpen(p)
                    done++
                    if (done % 16 == 0 || done == ports.size) onProgress(done, ports.size)
                }
            }
        }.awaitAll()
    }

    fun portRange(spec: String): List<Int> {
        val out = LinkedHashSet<Int>()
        for (part in spec.split(",")) {
            val t = part.trim()
            if (t.isEmpty()) continue
            if (t.contains("-")) {
                val (a, b) = t.split("-").map { it.trim().toIntOrNull() ?: return@portRange out.toList() }
                for (p in a..minOf(b, 65535)) out.add(p)
            } else t.toIntOrNull()?.let { out.add(it) }
        }
        return out.toList()
    }

    val serviceNames = mapOf(21 to "FTP", 22 to "SSH", 23 to "Telnet", 25 to "SMTP", 53 to "DNS", 80 to "HTTP", 110 to "POP3", 135 to "RPC", 139 to "NetBIOS",
        143 to "IMAP", 161 to "SNMP", 389 to "LDAP", 443 to "HTTPS", 445 to "SMB", 515 to "LPD", 631 to "IPP", 636 to "LDAPS", 993 to "IMAPS", 995 to "POP3S",
        1433 to "MSSQL", 1521 to "Oracle", 3306 to "MySQL", 3389 to "RDP", 5432 to "PostgreSQL", 5900 to "VNC", 8006 to "Proxmox", 8080 to "HTTP-alt",
        8443 to "HTTPS-alt", 9100 to "JetDirect", 62078 to "iPhone-sync")

    // ---------------- DNS ----------------

    suspend fun dnsLookup(name: String): List<String> = withContext(Dispatchers.IO) {
        runCatching { InetAddress.getAllByName(name).map { (it.hostAddress ?: "") + (it.canonicalHostName.takeIf { h -> h != it.hostAddress }?.let { h -> "  ($h)" } ?: "") } }.getOrElse { listOf("Errore: ${it.message}") }
    }

    // ---------------- throughput ----------------

    /** HTTP download throughput in Mbit/s over the given seconds. */
    suspend fun httpDownload(url: String, seconds: Int = 8, onProgress: suspend (Double) -> Unit): Double = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 5000; readTimeout = 10000; setRequestProperty("Cache-Control", "no-cache") }
        var bytes = 0L
        val t0 = System.nanoTime()
        val end = t0 + seconds * 1_000_000_000L
        var lastReport = t0
        conn.inputStream.use { ins ->
            val buf = ByteArray(64 * 1024)
            while (isActive) {
                val n = ins.read(buf)
                if (n < 0) break
                bytes += n
                val now = System.nanoTime()
                if (now - lastReport > 500_000_000L) { onProgress(bytes * 8.0 / ((now - t0) / 1e9) / 1e6); lastReport = now }
                if (now > end) break
            }
        }
        conn.disconnect()
        val secs = (System.nanoTime() - t0) / 1e9
        if (secs <= 0) 0.0 else bytes * 8.0 / secs / 1e6
    }

    /** HTTP upload throughput in Mbit/s (POST random bytes). */
    suspend fun httpUpload(url: String, seconds: Int = 8, onProgress: suspend (Double) -> Unit): Double = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 5000; readTimeout = 10000
            setChunkedStreamingMode(64 * 1024); setRequestProperty("Content-Type", "application/octet-stream")
        }
        val buf = ByteArray(64 * 1024) { (it * 31).toByte() }
        var bytes = 0L
        val t0 = System.nanoTime()
        val end = t0 + seconds * 1_000_000_000L
        var lastReport = t0
        conn.outputStream.use { out ->
            while (isActive && System.nanoTime() < end) {
                out.write(buf); bytes += buf.size
                val now = System.nanoTime()
                if (now - lastReport > 500_000_000L) { onProgress(bytes * 8.0 / ((now - t0) / 1e9) / 1e6); lastReport = now }
            }
        }
        runCatching { conn.responseCode }
        conn.disconnect()
        val secs = (System.nanoTime() - t0) / 1e9
        if (secs <= 0) 0.0 else bytes * 8.0 / secs / 1e6
    }

    // ---------------- connection info ----------------

    @Suppress("DEPRECATION")
    fun connectionInfo(ctx: Context): ConnectionInfo {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val info = runCatching { wm.connectionInfo }.getOrNull()
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val lp: LinkProperties? = net?.let { cm.getLinkProperties(it) }
        val v4 = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }
        val gw = lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        val ssid = info?.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" }
        val freq = info?.frequency?.takeIf { it > 0 }
        val standard = if (Build.VERSION.SDK_INT >= 30) when (info?.wifiStandard) {
            4 -> "802.11n (Wi-Fi 4)"; 5 -> "802.11ac (Wi-Fi 5)"; 6 -> "802.11ax (Wi-Fi 6)"; 8 -> "802.11be (Wi-Fi 7)"; 1 -> "802.11a/b/g"; else -> null
        } else null
        return ConnectionInfo(
            ssid = ssid, bssid = info?.bssid?.takeIf { it != "02:00:00:00:00:00" }, rssi = info?.rssi?.takeIf { it != -127 },
            linkMbps = info?.linkSpeed?.takeIf { it > 0 },
            txMbps = if (Build.VERSION.SDK_INT >= 29) info?.txLinkSpeedMbps?.takeIf { it > 0 } else null,
            rxMbps = if (Build.VERSION.SDK_INT >= 29) info?.rxLinkSpeedMbps?.takeIf { it > 0 } else null,
            freqMhz = freq, channel = freq?.let { Channels.channelOf(it) }, band = freq?.let { Channels.bandOf(it).label }, standard = standard,
            ip = v4?.address?.hostAddress, prefix = v4?.prefixLength, gateway = gw,
            dns = lp?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList(), mtu = if (Build.VERSION.SDK_INT >= 29) lp?.mtu else null,
            hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            externalIp = null
        )
    }

    suspend fun externalIp(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val c = (URL("https://api.ipify.org").openConnection() as HttpURLConnection).apply { connectTimeout = 4000; readTimeout = 4000 }
            c.inputStream.bufferedReader().readText().trim().also { c.disconnect() }
        }.getOrNull()
    }

    fun gateway(ctx: Context): String? = connectionInfo(ctx).gateway

    /** Local /24 (or the real prefix) as CIDR, for the subnet scan default. */
    fun localCidr(ctx: Context): String? {
        val c = connectionInfo(ctx)
        val ip = c.ip ?: return null
        val prefix = c.prefix ?: 24
        val v = ipToLong(ip) ?: return null
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return longToIp(v and mask) + "/" + prefix
    }

    // ---------------- process helpers ----------------

    private suspend fun runProcess(cmd: List<String>, timeoutSec: Long): String = withContext(Dispatchers.IO) { runProcessBlocking(cmd, timeoutSec) }

    private fun runProcessBlocking(cmd: List<String>, timeoutSec: Long): String {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        val p = runCatching { pb.start() }.getOrElse { return "exec error: ${it.message}" }
        val sb = StringBuilder()
        val reader = Thread {
            runCatching { BufferedReader(InputStreamReader(p.inputStream)).use { r -> r.lineSequence().forEach { sb.append(it).append('\n') } } }
        }.apply { start() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) p.destroy()
        reader.join(1000)
        return sb.toString()
    }

    /** OUI vendor lookup from the bundled table (first 3 bytes). */
    fun vendor(ctx: Context, mac: String?): String? {
        if (mac == null) return null
        val key = mac.uppercase().replace(":", "").replace("-", "").take(6)
        return OuiTable.lookup(ctx, key)
    }
}
