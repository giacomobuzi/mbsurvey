package it.buzi.mbsurvey.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import it.buzi.mbsurvey.R
import it.buzi.mbsurvey.databinding.ActivityNetToolsBinding
import it.buzi.mbsurvey.net.NetTools
import it.buzi.mbsurvey.wifi.ScanBus
import it.buzi.mbsurvey.wifi.WifiScanService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.Locale

class NetToolsActivity : AppCompatActivity() {

    private enum class Tool { INFO, PING, TRACE, SCAN, PORTS, DNS, SPEED, WIFI }

    private lateinit var b: ActivityNetToolsBinding
    private var tool = Tool.INFO
    private var job: Job? = null
    private val log = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityNetToolsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        b.chips.setOnCheckedStateChangeListener { _, ids ->
            val id = ids.firstOrNull() ?: return@setOnCheckedStateChangeListener
            tool = when (id) {
                b.chipPing.id -> Tool.PING; b.chipTrace.id -> Tool.TRACE; b.chipScan.id -> Tool.SCAN; b.chipPorts.id -> Tool.PORTS
                b.chipDns.id -> Tool.DNS; b.chipSpeed.id -> Tool.SPEED; b.chipWifi.id -> Tool.WIFI; else -> Tool.INFO
            }
            stop()
            configure()
        }
        b.runBtn.setOnClickListener { if (job?.isActive == true) stop() else run() }
        configure()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.tool_share).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, log.toString()), getString(R.string.tool_share)))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun configure() {
        val gw = NetTools.gateway(this) ?: "8.8.8.8"
        b.input1.visibility = View.VISIBLE; b.input2.visibility = View.VISIBLE
        when (tool) {
            Tool.INFO -> { b.toolHelp.setText(R.string.tool_help_info); b.input1.visibility = View.GONE; b.input2.visibility = View.GONE }
            Tool.PING -> { b.toolHelp.setText(R.string.tool_help_ping); b.input1.setHint(R.string.tool_hint_host); b.input1.setText(gw); b.input2.setHint(R.string.tool_hint_count); b.input2.setText("10") }
            Tool.TRACE -> { b.toolHelp.setText(R.string.tool_help_trace); b.input1.setHint(R.string.tool_hint_host); b.input1.setText("8.8.8.8"); b.input2.visibility = View.GONE }
            Tool.SCAN -> { b.toolHelp.setText(R.string.tool_help_scan); b.input1.setHint(R.string.tool_hint_cidr); b.input1.setText(NetTools.localCidr(this) ?: "192.168.1.0/24"); b.input2.setHint(R.string.tool_hint_ports); b.input2.setText("22,80,443,445") }
            Tool.PORTS -> { b.toolHelp.setText(R.string.tool_help_ports); b.input1.setHint(R.string.tool_hint_host); b.input1.setText(gw); b.input2.setHint(R.string.tool_hint_ports); b.input2.setText("1-1024") }
            Tool.DNS -> { b.toolHelp.setText(R.string.tool_help_dns); b.input1.setHint(R.string.tool_hint_name); b.input1.setText("google.com"); b.input2.visibility = View.GONE }
            Tool.SPEED -> { b.toolHelp.setText(R.string.tool_help_speed); b.input1.setHint(R.string.tool_hint_url); b.input1.setText("https://speed.cloudflare.com/__down?bytes=200000000"); b.input2.setHint(R.string.tool_hint_url_up); b.input2.setText("https://speed.cloudflare.com/__up") }
            Tool.WIFI -> { b.toolHelp.setText(R.string.tool_help_wifi); b.input1.visibility = View.GONE; b.input2.visibility = View.GONE }
        }
        clear()
        if (tool == Tool.INFO) run()
    }

    private fun clear() { log.setLength(0); b.output.text = "" }

    private fun out(line: String) {
        log.append(line).append('\n')
        b.output.text = log
        b.scroll.post { b.scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun replaceLast(line: String) {
        val idx = log.lastIndexOf("\n", log.length - 2)
        log.setLength(if (idx < 0) 0 else idx + 1)
        out(line)
    }

    private fun stop() {
        job?.cancel(); job = null
        b.runBtn.setText(R.string.tool_run)
        b.progress.visibility = View.INVISIBLE
    }

    private fun run() {
        val a = b.input1.text.toString().trim()
        val c = b.input2.text.toString().trim()
        clear()
        b.runBtn.setText(R.string.tool_stop)
        b.progress.visibility = View.VISIBLE
        b.progress.isIndeterminate = true
        job = lifecycleScope.launch {
            try {
                when (tool) {
                    Tool.INFO -> info()
                    Tool.PING -> ping(a, c.toIntOrNull() ?: 10)
                    Tool.TRACE -> trace(a)
                    Tool.SCAN -> scan(a, c)
                    Tool.PORTS -> ports(a, c)
                    Tool.DNS -> dns(a)
                    Tool.SPEED -> speed(a, c)
                    Tool.WIFI -> wifi()
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) out("Errore: ${e.message}")
            } finally {
                b.runBtn.setText(R.string.tool_run)
                b.progress.visibility = View.INVISIBLE
            }
        }
    }

    private suspend fun info() {
        val i = withContext(Dispatchers.IO) { NetTools.connectionInfo(this@NetToolsActivity) }
        out("SSID        ${i.ssid ?: "-"}")
        out("BSSID       ${i.bssid ?: "-"}   ${NetTools.vendor(this, i.bssid) ?: ""}")
        out("Segnale     ${i.rssi?.let { "$it dBm" } ?: "-"}")
        out("Banda       ${i.band ?: "-"}   canale ${i.channel ?: "-"}   ${i.freqMhz?.let { "$it MHz" } ?: ""}")
        out("Standard    ${i.standard ?: "-"}")
        out("Link        ${i.linkMbps?.let { "$it Mbps" } ?: "-"}   TX ${i.txMbps ?: "-"}   RX ${i.rxMbps ?: "-"}")
        out("IP          ${i.ip ?: "-"}${i.prefix?.let { "/$it" } ?: ""}")
        out("Gateway     ${i.gateway ?: "-"}")
        out("DNS         ${i.dns.joinToString(", ").ifBlank { "-" }}")
        out("MTU         ${i.mtu ?: "-"}")
        out("Internet    ${if (i.hasInternet) "sì" else "no"}")
        out("IP pubblico …")
        val ext = NetTools.externalIp()
        replaceLast("IP pubblico ${ext ?: "-"}")
        if (i.gateway != null) {
            out("")
            out("Ping gateway ${i.gateway} …")
            val p = NetTools.probe(i.gateway, 5)
            replaceLast("Ping gateway ${i.gateway}: ${p.received}/${p.sent} risposte, media ${fmt(p.avgMs)} ms, max ${fmt(p.maxMs)} ms")
        }
        out("Ping 1.1.1.1 …")
        val p2 = NetTools.probe("1.1.1.1", 5)
        replaceLast("Ping internet (1.1.1.1): ${p2.received}/${p2.sent} risposte, media ${fmt(p2.avgMs)} ms")
    }

    private suspend fun ping(host: String, count: Int) {
        out("PING $host, $count pacchetti")
        val rtts = ArrayList<Double>()
        var sent = 0; var lost = 0
        repeat(count.coerceIn(1, 500)) { i ->
            val r = NetTools.ping(host, 1, 2, null, 0.2)
            sent++
            if (r.received > 0 && r.avgMs != null) { rtts.add(r.avgMs); out("seq=${i + 1}  ${fmt(r.avgMs)} ms${r.ip?.let { "  ($it)" } ?: ""}") }
            else { lost++; out("seq=${i + 1}  timeout") }
            delay(300)
        }
        out("")
        out("--- $sent inviati, ${sent - lost} ricevuti, perdita ${lost * 100 / sent} %")
        if (rtts.isNotEmpty()) {
            val avg = rtts.average()
            val jitter = if (rtts.size > 1) rtts.zipWithNext { x, y -> kotlin.math.abs(y - x) }.average() else 0.0
            out("min ${fmt(rtts.min())} / media ${fmt(avg)} / max ${fmt(rtts.max())} ms · jitter ${fmt(jitter)} ms")
        }
    }

    private suspend fun trace(host: String) {
        out("traceroute $host")
        NetTools.traceroute(host) { h ->
            out("%2d  %-16s %s  %s".format(h.ttl, h.ip ?: "*", h.host ?: "", h.rttMs?.let { "${fmt(it)} ms" } ?: ""))
        }
        out("fine")
    }

    private suspend fun scan(cidr: String, portSpec: String) {
        val hosts = NetTools.hostsOf(cidr)
        if (hosts.isEmpty()) { out("Subnet non valida"); return }
        if (hosts.size > 1024) out(getString(R.string.tool_big_subnet, hosts.size))
        val ports = NetTools.portRange(portSpec).ifEmpty { listOf(80, 443) }
        out("Scansione $cidr (${hosts.size} host), porte ${ports.joinToString(",")}")
        b.progress.isIndeterminate = false; b.progress.max = hosts.size
        val found = ArrayList<NetTools.HostInfo>()
        NetTools.scanSubnet(cidr, ports, onProgress = { d, _ -> b.progress.progress = d }) { h ->
            found.add(h)
            out("%-15s %-6s %s %s".format(h.ip, h.rttMs?.let { "${fmt(it)}ms" } ?: "tcp", h.hostname ?: "", if (h.openPorts.isEmpty()) "" else "[${h.openPorts.joinToString(",")}]"))
        }
        val macs = withContext(Dispatchers.IO) { NetTools.neighbourMacs() }
        out("")
        out("--- ${found.size} host attivi")
        for (h in found.sortedBy { ipKey(it.ip) }) {
            val mac = macs[h.ip]
            val vendor = NetTools.vendor(this, mac)
            out("%-15s %-17s %s".format(h.ip, mac ?: "", listOfNotNull(vendor, h.hostname).joinToString(" · ")))
        }
    }

    private fun ipKey(ip: String): Long = ip.split(".").fold(0L) { acc, s -> (acc shl 8) or (s.toLongOrNull() ?: 0) }

    private suspend fun ports(host: String, spec: String) {
        val list = NetTools.portRange(spec)
        if (list.isEmpty()) { out("Porte non valide"); return }
        out("Scansione porte su $host (${list.size} porte)")
        b.progress.isIndeterminate = false; b.progress.max = list.size
        var n = 0
        NetTools.scanPorts(host, list, 400, onOpen = { p -> n++; out("%5d  aperta  %s".format(p, NetTools.serviceNames[p] ?: "")) }, onProgress = { d, _ -> b.progress.progress = d })
        out("--- $n porte aperte")
    }

    private suspend fun dns(name: String) {
        out("Risoluzione $name")
        NetTools.dnsLookup(name).forEach { out("  $it") }
    }

    private suspend fun speed(down: String, up: String) {
        if (down.isNotBlank()) {
            out("Download da $down …")
            val d = NetTools.httpDownload(down, 8) { mbps -> replaceLast("Download: ${fmt(mbps)} Mbit/s …") }
            replaceLast("Download: ${fmt(d)} Mbit/s")
        }
        if (up.isNotBlank()) {
            out("Upload verso $up …")
            val u = NetTools.httpUpload(up, 8) { mbps -> replaceLast("Upload: ${fmt(mbps)} Mbit/s …") }
            replaceLast("Upload: ${fmt(u)} Mbit/s")
        }
        val gw = NetTools.gateway(this)
        if (gw != null) {
            out("Latenza gateway …")
            val p = NetTools.probe(gw, 10)
            replaceLast("Latenza gateway: media ${fmt(p.avgMs)} ms, max ${fmt(p.maxMs)} ms, perdita ${p.lossPct} %")
        }
    }

    private suspend fun wifi() {
        if (!WifiScanService.hasPermissions(this)) { out(getString(R.string.perm_needed)); return }
        WifiScanService.start(this, 3000)
        out("Scansione in corso… (aggiornamento continuo, premi Stop per fermare)")
        try {
            ScanBus.scans.collect { scan ->
                log.setLength(0)
                val byBand = scan.entries.sortedWith(compareBy({ it.band.ordinal }, { it.channel }, { -it.rssi }))
                out("${scan.entries.size} reti · ${java.text.SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(java.util.Date(scan.t))}")
                var lastBand: String? = null
                for (e in byBand) {
                    if (e.band.label != lastBand) { out(""); out("== ${e.band.label}"); lastBand = e.band.label }
                    val vendor = NetTools.vendor(this@NetToolsActivity, e.bssid) ?: ""
                    out("%-24s %4d dBm  ch %3d/%-3d %-5s %s".format(e.ssid.ifBlank { "<nascosto>" }.take(24), e.rssi, e.channel, e.widthMhz, e.security, e.bssid))
                    if (vendor.isNotBlank()) out("    $vendor")
                }
            }
        } finally {
            WifiScanService.stop(this)
        }
    }

    private fun fmt(v: Double?): String = if (v == null) "-" else String.format(Locale.ITALY, "%.1f", v)

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }
}
