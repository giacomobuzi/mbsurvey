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

    private enum class Tool { INFO, PING, TRACE, SCAN, PORTS, DNS, SPEED, WIFI, CHANNELS }

    companion object { const val EXTRA_PROJECT = "projectId" }

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
                b.chipDns.id -> Tool.DNS; b.chipSpeed.id -> Tool.SPEED; b.chipWifi.id -> Tool.WIFI; b.chipChannels.id -> Tool.CHANNELS; else -> Tool.INFO
            }
            stop()
            configure()
        }
        b.runBtn.setOnClickListener { if (job?.isActive == true) stop() else run() }
        val pid = intent.getStringExtra(EXTRA_PROJECT)
        if (pid != null) {
            // Opened from a project: show the channel chart built from the project's measurements.
            val p = runCatching { it.buzi.mbsurvey.data.ProjectStore(this).load(pid) }.getOrNull()
            if (p != null) {
                b.chips.check(b.chipChannels.id)
                val radios = it.buzi.mbsurvey.survey.SurveyEngine.radios(p)
                b.chart.entries = radios.map { r -> it.buzi.mbsurvey.model.ScanEntry(r.bssid, r.ssid, r.maxRssi, r.freqMhz, r.widthMhz, 0, r.capabilities) }
                b.toolHelp.text = getString(R.string.channels_project)
                b.chart.visibility = View.VISIBLE; b.scroll.visibility = View.GONE
                return
            }
        }
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
            Tool.CHANNELS -> { b.toolHelp.setText(R.string.tool_help_channels); b.input1.visibility = View.GONE; b.input2.visibility = View.GONE }
        }
        val chart = tool == Tool.CHANNELS
        b.chart.visibility = if (chart) View.VISIBLE else View.GONE
        b.scroll.visibility = if (chart) View.GONE else View.VISIBLE
        clear()
        if (tool == Tool.INFO || tool == Tool.CHANNELS) run()
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
                    Tool.CHANNELS -> channels()
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) out(getString(R.string.nt_error, e.message ?: ""))
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
        out(getString(R.string.nt_lbl_signal) + "     ${i.rssi?.let { "$it dBm" } ?: "-"}")
        out(getString(R.string.nt_lbl_band) + "       ${i.band ?: "-"}   ${getString(R.string.nt_lbl_channel)} ${i.channel ?: "-"}   ${i.freqMhz?.let { "$it MHz" } ?: ""}")
        out("Standard    ${i.standard ?: "-"}")
        out("Link        ${i.linkMbps?.let { "$it Mbps" } ?: "-"}   TX ${i.txMbps ?: "-"}   RX ${i.rxMbps ?: "-"}")
        out("IP          ${i.ip ?: "-"}${i.prefix?.let { "/$it" } ?: ""}")
        out("Gateway     ${i.gateway ?: "-"}")
        out("DNS         ${i.dns.joinToString(", ").ifBlank { "-" }}")
        out("MTU         ${i.mtu ?: "-"}")
        out("Internet    ${getString(if (i.hasInternet) R.string.yes else R.string.no)}")
        out(getString(R.string.nt_lbl_public_ip) + " …")
        val ext = NetTools.externalIp()
        replaceLast(getString(R.string.nt_lbl_public_ip) + " ${ext ?: "-"}")
        if (i.gateway != null) {
            out("")
            out("Ping gateway ${i.gateway} …")
            val p = NetTools.probe(i.gateway, 5)
            replaceLast(getString(R.string.nt_ping_gw_result, i.gateway, p.received, p.sent, fmt(p.avgMs), fmt(p.maxMs)))
        }
        out("Ping 1.1.1.1 …")
        val p2 = NetTools.probe("1.1.1.1", 5)
        replaceLast(getString(R.string.nt_ping_inet_result, p2.received, p2.sent, fmt(p2.avgMs)))
    }

    private suspend fun ping(host: String, count: Int) {
        out(getString(R.string.nt_ping_header, host, count))
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
        out(getString(R.string.nt_ping_stats, sent, sent - lost, lost * 100 / sent))
        if (rtts.isNotEmpty()) {
            val avg = rtts.average()
            val jitter = if (rtts.size > 1) rtts.zipWithNext { x, y -> kotlin.math.abs(y - x) }.average() else 0.0
            out(getString(R.string.nt_ping_rtt, fmt(rtts.min()), fmt(avg), fmt(rtts.max()), fmt(jitter)))
        }
    }

    private suspend fun trace(host: String) {
        out("traceroute $host")
        NetTools.traceroute(host) { h ->
            out("%2d  %-16s %s  %s".format(h.ttl, h.ip ?: "*", h.host ?: "", h.rttMs?.let { "${fmt(it)} ms" } ?: ""))
        }
        out(getString(R.string.nt_done))
    }

    private suspend fun scan(cidr: String, portSpec: String) {
        val hosts = NetTools.hostsOf(cidr)
        if (hosts.isEmpty()) { out(getString(R.string.nt_bad_subnet)); return }
        if (hosts.size > 1024) out(getString(R.string.tool_big_subnet, hosts.size))
        val ports = NetTools.portRange(portSpec).ifEmpty { listOf(80, 443) }
        out(getString(R.string.nt_scanning, cidr, hosts.size, ports.joinToString(",")))
        b.progress.isIndeterminate = false; b.progress.max = hosts.size
        val found = ArrayList<NetTools.HostInfo>()
        NetTools.scanSubnet(cidr, ports, onProgress = { d, _ -> b.progress.progress = d }) { h ->
            found.add(h)
            out("%-15s %-6s %s %s".format(h.ip, h.rttMs?.let { "${fmt(it)}ms" } ?: "tcp", h.hostname ?: "", if (h.openPorts.isEmpty()) "" else "[${h.openPorts.joinToString(",")}]"))
        }
        val macs = withContext(Dispatchers.IO) { NetTools.neighbourMacs() }
        out("")
        out(getString(R.string.nt_hosts_found, found.size))
        for (h in found.sortedBy { ipKey(it.ip) }) {
            val mac = macs[h.ip]
            val vendor = NetTools.vendor(this, mac)
            out("%-15s %-17s %s".format(h.ip, mac ?: "", listOfNotNull(vendor, h.hostname).joinToString(" · ")))
        }
    }

    private fun ipKey(ip: String): Long = ip.split(".").fold(0L) { acc, s -> (acc shl 8) or (s.toLongOrNull() ?: 0) }

    private suspend fun ports(host: String, spec: String) {
        val list = NetTools.portRange(spec)
        if (list.isEmpty()) { out(getString(R.string.nt_bad_ports)); return }
        out(getString(R.string.nt_port_scan, host, list.size))
        b.progress.isIndeterminate = false; b.progress.max = list.size
        var n = 0
        NetTools.scanPorts(host, list, 400, onOpen = { p -> n++; out("%5d  " + getString(R.string.nt_open) + "  %s".format(p, NetTools.serviceNames[p] ?: "")) }, onProgress = { d, _ -> b.progress.progress = d })
        out(getString(R.string.nt_ports_open, n))
    }

    private suspend fun dns(name: String) {
        out(getString(R.string.nt_resolving, name))
        NetTools.dnsLookup(name).forEach { out("  $it") }
    }

    private suspend fun speed(down: String, up: String) {
        if (down.isNotBlank()) {
            out(getString(R.string.nt_download_from, down))
            val d = NetTools.httpDownload(down, 8) { mbps -> replaceLast("Download: ${fmt(mbps)} Mbit/s …") }
            replaceLast("Download: ${fmt(d)} Mbit/s")
        }
        if (up.isNotBlank()) {
            out(getString(R.string.nt_upload_to, up))
            val u = NetTools.httpUpload(up, 8) { mbps -> replaceLast("Upload: ${fmt(mbps)} Mbit/s …") }
            replaceLast("Upload: ${fmt(u)} Mbit/s")
        }
        val gw = NetTools.gateway(this)
        if (gw != null) {
            out(getString(R.string.nt_gw_latency) + " …")
            val p = NetTools.probe(gw, 10)
            replaceLast(getString(R.string.nt_gw_latency_result, fmt(p.avgMs), fmt(p.maxMs), p.lossPct))
        }
    }

    private suspend fun wifi() {
        if (!WifiScanService.hasPermissions(this)) { out(getString(R.string.perm_needed)); return }
        WifiScanService.start(this, 3000)
        out(getString(R.string.nt_wifi_scanning))
        try {
            ScanBus.scans.collect { scan ->
                log.setLength(0)
                val byBand = scan.entries.sortedWith(compareBy({ it.band.ordinal }, { it.channel }, { -it.rssi }))
                out("${scan.entries.size} ${getString(R.string.nt_networks)} · ${java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date(scan.t))}")
                var lastBand: String? = null
                for (e in byBand) {
                    if (e.band.label != lastBand) { out(""); out("== ${e.band.label}"); lastBand = e.band.label }
                    val vendor = NetTools.vendor(this@NetToolsActivity, e.bssid) ?: ""
                    out("%-24s %4d dBm  ch %3d/%-3d %-5s %s".format(e.ssid.ifBlank { getString(R.string.nt_hidden) }.take(24), e.rssi, e.channel, e.widthMhz, e.security, e.bssid))
                    if (vendor.isNotBlank()) out("    $vendor")
                }
            }
        } finally {
            WifiScanService.stop(this)
        }
    }

    private suspend fun channels() {
        if (!WifiScanService.hasPermissions(this)) { out(getString(R.string.perm_needed)); return }
        WifiScanService.start(this, 3000)
        ScanBus.lastScan.value?.let { b.chart.entries = it.entries }
        try {
            ScanBus.scans.collect { scan -> b.chart.entries = scan.entries }
        } finally {
            WifiScanService.stop(this)
        }
    }

    private fun fmt(v: Double?): String = if (v == null) "-" else String.format(Locale.getDefault(), "%.1f", v)

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }
}
