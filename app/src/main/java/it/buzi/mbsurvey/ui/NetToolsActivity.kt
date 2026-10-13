package it.buzi.mbsurvey.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import it.buzi.mbsurvey.R
import it.buzi.mbsurvey.data.ProjectStore
import it.buzi.mbsurvey.databinding.ActivityNetToolsBinding
import it.buzi.mbsurvey.model.Band
import it.buzi.mbsurvey.model.ScanEntry
import it.buzi.mbsurvey.net.NetTools
import it.buzi.mbsurvey.survey.SurveyEngine
import it.buzi.mbsurvey.wifi.ScanBus
import it.buzi.mbsurvey.wifi.WifiScanService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NetToolsActivity : BaseActivity() {

    private enum class Tool { INFO, PING, TRACE, SCAN, PORTS, DNS, SPEED, WIFI, CHANNELS }

    companion object { const val EXTRA_PROJECT = "projectId" }

    private lateinit var b: ActivityNetToolsBinding
    private val adapter = ResultAdapter()
    private var tool = Tool.INFO
    private var job: Job? = null

    private val green = Color.rgb(11, 140, 79)
    private val amber = Color.rgb(244, 163, 0)
    private val red = Color.rgb(210, 40, 40)
    private val blue = Color.rgb(30, 100, 200)
    private val purple = Color.rgb(120, 60, 160)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityNetToolsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.results.layoutManager = LinearLayoutManager(this)
        b.results.adapter = adapter
        b.results.itemAnimator = null

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
            val p = runCatching { ProjectStore(this).load(pid) }.getOrNull()
            if (p != null) {
                b.chips.check(b.chipChannels.id)
                tool = Tool.CHANNELS
                val radios = SurveyEngine.radios(p)
                b.chart.entries = radios.map { r -> ScanEntry(r.bssid, r.ssid, r.maxRssi, r.freqMhz, r.widthMhz, 0, r.capabilities) }
                b.toolHelp.text = getString(R.string.channels_project)
                b.input1.visibility = View.GONE; b.input2.visibility = View.GONE; b.runBtn.visibility = View.GONE
                b.chart.visibility = View.VISIBLE; b.results.visibility = View.GONE
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
            val text = (b.summary.text.toString().takeIf { it.isNotBlank() }?.plus("\n\n") ?: "") + adapter.text()
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), getString(R.string.tool_share)))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ---------------- setup ----------------

    private fun configure() {
        val gw = NetTools.gateway(this) ?: "8.8.8.8"
        b.input1.visibility = View.VISIBLE; b.input2.visibility = View.VISIBLE; b.runBtn.visibility = View.VISIBLE
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
        b.results.visibility = if (chart) View.GONE else View.VISIBLE
        clear()
        if (tool == Tool.INFO || tool == Tool.CHANNELS || tool == Tool.WIFI) run()
    }

    private fun clear() { adapter.clear(); summary(null) }

    private fun summary(text: String?) {
        b.summary.text = text ?: ""
        b.summary.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun add(item: ResultItem): ResultItem {
        adapter.add(item)
        b.results.scrollToPosition(adapter.itemCount - 1)
        return item
    }

    private fun setRunning(running: Boolean) {
        b.runBtn.setText(if (running) R.string.tool_stop else R.string.tool_run)
        b.progress.visibility = if (running) View.VISIBLE else View.INVISIBLE
        b.progress.isIndeterminate = true
    }

    private fun stop() {
        job?.cancel(); job = null
        setRunning(false)
    }

    private fun run() {
        val a = b.input1.text.toString().trim()
        val c = b.input2.text.toString().trim()
        clear()
        setRunning(true)
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                add(ResultItem(getString(R.string.nt_error, e.message ?: ""), badge = "!", badgeColor = red))
            } finally {
                setRunning(false)
            }
        }
    }

    private fun determinate(max: Int) { b.progress.isIndeterminate = false; b.progress.max = max; b.progress.progress = 0 }

    private fun latencyBar(ms: Double?): Float? = ms?.let { (it / 300.0).toFloat().coerceIn(0.03f, 1f) }
    private fun latencyColor(ms: Double?): Int = ms?.let { BarView.latencyColor(it) } ?: red

    // ---------------- tools ----------------

    private suspend fun info() {
        val i = withContext(Dispatchers.IO) { NetTools.connectionInfo(this@NetToolsActivity) }
        val rssi = i.rssi
        add(ResultItem(i.ssid ?: "—", listOfNotNull(i.bssid, NetTools.vendor(this, i.bssid), i.standard).joinToString(" · "),
            rssi?.let { "$it dBm" } ?: "", rssi?.let { BarView.rssiFraction(it) }, rssi?.let { BarView.rssiColor(it) } ?: green, "SSID"))
        add(ResultItem(getString(R.string.nt_lbl_band), "${i.band ?: "-"} · ${getString(R.string.nt_lbl_channel)} ${i.channel ?: "-"}" + (i.freqMhz?.let { " · $it MHz" } ?: "")))
        add(ResultItem("Link", "TX ${i.txMbps ?: "-"} Mbps · RX ${i.rxMbps ?: "-"} Mbps", i.linkMbps?.let { "$it Mbps" } ?: ""))
        add(ResultItem("IP", "Gateway ${i.gateway ?: "-"} · MTU ${i.mtu ?: "-"}", (i.ip ?: "-") + (i.prefix?.let { "/$it" } ?: "")))
        add(ResultItem("DNS", i.dns.joinToString(", ").ifBlank { "-" }))
        val inet = add(ResultItem("Internet", "", getString(if (i.hasInternet) R.string.yes else R.string.no), badge = "WAN", badgeColor = if (i.hasInternet) green else red))
        val ext = NetTools.externalIp()
        inet.subtitle = getString(R.string.nt_lbl_public_ip) + ": " + (ext ?: "-"); adapter.update(inet)
        if (i.gateway != null) {
            val row = add(ResultItem("Ping gateway ${i.gateway}", "…"))
            val p = NetTools.probe(i.gateway, 5)
            row.subtitle = getString(R.string.nt_ping_gw_result, i.gateway, p.received, p.sent, fmt(p.avgMs), fmt(p.maxMs))
            row.value = p.avgMs?.let { "${fmt(it)} ms" } ?: "—"; row.bar = latencyBar(p.avgMs); row.barColor = latencyColor(p.avgMs)
            adapter.update(row)
        }
        val row2 = add(ResultItem("Ping internet 1.1.1.1", "…"))
        val p2 = NetTools.probe("1.1.1.1", 5)
        row2.subtitle = getString(R.string.nt_ping_inet_result, p2.received, p2.sent, fmt(p2.avgMs))
        row2.value = p2.avgMs?.let { "${fmt(it)} ms" } ?: "—"; row2.bar = latencyBar(p2.avgMs); row2.barColor = latencyColor(p2.avgMs)
        adapter.update(row2)
    }

    private suspend fun ping(host: String, count: Int) {
        summary(getString(R.string.nt_ping_header, host, count))
        val rtts = ArrayList<Double>()
        var sent = 0; var lost = 0
        repeat(count.coerceIn(1, 500)) { i ->
            val r = NetTools.ping(host, 1, 2, null, 0.2)
            sent++
            if (r.received > 0 && r.avgMs != null) {
                rtts.add(r.avgMs)
                add(ResultItem("seq ${i + 1}", r.ip ?: "", "${fmt(r.avgMs)} ms", latencyBar(r.avgMs), latencyColor(r.avgMs)))
            } else {
                lost++
                add(ResultItem("seq ${i + 1}", "timeout", "—", 1f, red, "✕", red))
            }
            val stats = getString(R.string.nt_ping_stats, sent, sent - lost, lost * 100 / sent).trimStart('-', ' ')
            summary(stats + if (rtts.isNotEmpty()) "\n" + getString(R.string.nt_ping_rtt, fmt(rtts.min()), fmt(rtts.average()), fmt(rtts.max()), fmt(jitter(rtts))) else "")
            delay(300)
        }
    }

    private fun jitter(r: List<Double>): Double = if (r.size > 1) r.zipWithNext { x, y -> kotlin.math.abs(y - x) }.average() else 0.0

    private suspend fun trace(host: String) {
        summary("traceroute $host")
        NetTools.traceroute(host) { h ->
            add(ResultItem(h.ip ?: "*", h.host ?: "", h.rttMs?.let { "${fmt(it)} ms" } ?: "", latencyBar(h.rttMs), latencyColor(h.rttMs), h.ttl.toString(), blue))
        }
        summary("traceroute $host · " + getString(R.string.nt_done))
    }

    private suspend fun scan(cidr: String, portSpec: String) {
        val hosts = NetTools.hostsOf(cidr)
        if (hosts.isEmpty()) { add(ResultItem(getString(R.string.nt_bad_subnet), badge = "!", badgeColor = red)); return }
        val ports = NetTools.portRange(portSpec).ifEmpty { listOf(80, 443) }
        summary(getString(R.string.nt_scanning, cidr, hosts.size, ports.joinToString(",")) + if (hosts.size > 1024) "\n" + getString(R.string.tool_big_subnet, hosts.size) else "")
        determinate(hosts.size)
        val found = ArrayList<Pair<NetTools.HostInfo, ResultItem>>()
        NetTools.scanSubnet(cidr, ports, onProgress = { d, _ -> b.progress.progress = d }) { h ->
            val services = h.openPorts.joinToString(" ") { p -> NetTools.serviceNames[p] ?: p.toString() }
            val item = add(ResultItem(h.ip, listOfNotNull(h.hostname, services.takeIf { it.isNotBlank() }).joinToString(" · "),
                h.rttMs?.let { "${fmt(it)} ms" } ?: "tcp", latencyBar(h.rttMs ?: 1.0), if (h.rttMs != null) BarView.latencyColor(h.rttMs) else blue))
            found.add(Pair(h, item))
        }
        val macs = withContext(Dispatchers.IO) { NetTools.neighbourMacs() }
        for ((h, item) in found) {
            val mac = macs[h.ip] ?: continue
            val vendor = NetTools.vendor(this, mac)
            item.subtitle = listOfNotNull(vendor, mac, item.subtitle.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (vendor != null) item.badge = vendor.take(12)
            adapter.update(item)
        }
        summary(getString(R.string.nt_hosts_found, found.size).trimStart('-', ' ') + " · $cidr")
    }

    private suspend fun ports(host: String, spec: String) {
        val list = NetTools.portRange(spec)
        if (list.isEmpty()) { add(ResultItem(getString(R.string.nt_bad_ports), badge = "!", badgeColor = red)); return }
        summary(getString(R.string.nt_port_scan, host, list.size))
        determinate(list.size)
        var n = 0
        NetTools.scanPorts(host, list, 400, onOpen = { p ->
            n++
            add(ResultItem("$p", NetTools.serviceNames[p] ?: "", getString(R.string.nt_open), badge = "TCP", badgeColor = green))
        }, onProgress = { d, _ -> b.progress.progress = d })
        summary(getString(R.string.nt_ports_open, n).trimStart('-', ' ') + " · $host")
    }

    private suspend fun dns(name: String) {
        summary(getString(R.string.nt_resolving, name))
        NetTools.dnsLookup(name).forEach { line ->
            val parts = line.split("  (")
            add(ResultItem(parts[0].trim(), parts.getOrNull(1)?.trimEnd(')') ?: "", badge = if (parts[0].contains(':')) "IPv6" else "IPv4", badgeColor = blue))
        }
    }

    private suspend fun speed(down: String, up: String) {
        if (down.isNotBlank()) {
            val row = add(ResultItem("Download", down, "…", 0f, green))
            val d = NetTools.httpDownload(down, 8) { mbps -> row.value = "${fmt(mbps)} Mbit/s"; row.bar = (mbps / 500).toFloat(); adapter.update(row) }
            row.value = "${fmt(d)} Mbit/s"; row.bar = (d / 500).toFloat(); adapter.update(row)
        }
        if (up.isNotBlank()) {
            val row = add(ResultItem("Upload", up, "…", 0f, blue))
            val u = NetTools.httpUpload(up, 8) { mbps -> row.value = "${fmt(mbps)} Mbit/s"; row.bar = (mbps / 500).toFloat(); adapter.update(row) }
            row.value = "${fmt(u)} Mbit/s"; row.bar = (u / 500).toFloat(); adapter.update(row)
        }
        val gw = NetTools.gateway(this)
        if (gw != null) {
            val row = add(ResultItem(getString(R.string.nt_gw_latency), gw, "…"))
            val p = NetTools.probe(gw, 10)
            row.subtitle = getString(R.string.nt_gw_latency_result, fmt(p.avgMs), fmt(p.maxMs), p.lossPct)
            row.value = p.avgMs?.let { "${fmt(it)} ms" } ?: "—"; row.bar = latencyBar(p.avgMs); row.barColor = latencyColor(p.avgMs)
            adapter.update(row)
        }
    }

    private suspend fun wifi() {
        if (!WifiScanService.hasPermissions(this)) { add(ResultItem(getString(R.string.perm_needed), badge = "!", badgeColor = red)); return }
        WifiScanService.start(this, 3000)
        summary(getString(R.string.nt_wifi_scanning))
        ScanBus.lastScan.value?.let { showWifi(it.entries, it.t) }
        try {
            ScanBus.scans.collect { scan -> showWifi(scan.entries, scan.t) }
        } finally {
            WifiScanService.stop(this)
        }
    }

    private fun showWifi(entries: List<ScanEntry>, t: Long) {
        val list = entries.sortedByDescending { it.rssi }.map { e ->
            val vendor = NetTools.vendor(this, e.bssid)
            ResultItem(
                title = e.ssid.ifBlank { getString(R.string.nt_hidden) },
                subtitle = listOfNotNull(e.bssid, vendor, "ch ${e.channel} · ${e.widthMhz} MHz", e.security).joinToString(" · "),
                value = "${e.rssi} dBm",
                bar = BarView.rssiFraction(e.rssi), barColor = BarView.rssiColor(e.rssi),
                badge = e.band.label.replace(" GHz", ""), badgeColor = when (e.band) { Band.B24 -> amber; Band.B5 -> blue; else -> purple },
                key = e.bssid
            )
        }
        adapter.replaceAll(list)
        summary("${entries.size} ${getString(R.string.nt_networks)} · ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(t))}")
    }

    private suspend fun channels() {
        if (!WifiScanService.hasPermissions(this)) { summary(getString(R.string.perm_needed)); return }
        WifiScanService.start(this, 3000)
        ScanBus.lastScan.value?.let { b.chart.entries = it.entries }
        try {
            ScanBus.scans.collect { scan -> b.chart.entries = scan.entries; summary("${scan.entries.size} ${getString(R.string.nt_networks)}") }
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
