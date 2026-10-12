package it.buzi.wifisurvey.wifi

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import it.buzi.wifisurvey.R
import it.buzi.wifisurvey.model.Channels
import it.buzi.wifisurvey.model.Scan
import it.buzi.wifisurvey.model.ScanEntry
import it.buzi.wifisurvey.ui.ProjectsActivity

/**
 * Foreground service that keeps the Wi-Fi scanner running and publishes results on [ScanBus].
 * Scanning on Android 10+ requires location permission and location services switched on.
 */
class WifiScanService : Service() {

    companion object {
        const val ACTION_START = "it.buzi.wifisurvey.START"
        const val ACTION_STOP = "it.buzi.wifisurvey.STOP"
        const val EXTRA_INTERVAL = "interval"
        private const val CHANNEL_ID = "scan"
        private const val NOTIF_ID = 1

        fun start(ctx: Context, intervalMs: Long) {
            val i = Intent(ctx, WifiScanService::class.java).setAction(ACTION_START).putExtra(EXTRA_INTERVAL, intervalMs)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, WifiScanService::class.java).setAction(ACTION_STOP))
        }

        fun isThrottleEnabled(ctx: Context): Boolean {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (Build.VERSION.SDK_INT >= 30) {
                return runCatching { wm.isScanThrottleEnabled }.getOrDefault(true)
            }
            return Settings.Global.getInt(ctx.contentResolver, "wifi_scan_throttle_enabled", 1) == 1
        }

        fun hasPermissions(ctx: Context): Boolean {
            val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!fine) return false
            if (Build.VERSION.SDK_INT >= 33) {
                return ContextCompat.checkSelfPermission(ctx, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            }
            return true
        }
    }

    private lateinit var wifi: WifiManager
    private val handler = Handler(Looper.getMainLooper())
    private var intervalMs = 2500L
    private var running = false
    private var lastNewestTimestampUs = 0L
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                val ok = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true)
                publish(ok)
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                @Suppress("DEPRECATION")
                val started = wifi.startScan()
                if (!started) {
                    ScanBus.throttled.value = true
                }
            } catch (e: Exception) {
                ScanBus.lastError.value = e.message
            }
            handler.postDelayed(this, intervalMs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopScanning()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                intervalMs = intent?.getLongExtra(EXTRA_INTERVAL, 2500L) ?: 2500L
                startInForeground()
                startScanning()
            }
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun startScanning() {
        if (running) return
        running = true
        ScanBus.running.value = true
        ScanBus.throttled.value = isThrottleEnabled(this)
        ScanBus.lastError.value = null
        if (!registered) {
            val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
        handler.post(tick)
    }

    private fun stopScanning() {
        running = false
        ScanBus.running.value = false
        handler.removeCallbacks(tick)
        if (registered) {
            runCatching { unregisterReceiver(receiver) }
            registered = false
        }
    }

    @Suppress("DEPRECATION")
    private fun publish(updated: Boolean) {
        if (!running) return
        val results: List<ScanResult> = try {
            wifi.scanResults
        } catch (e: SecurityException) {
            ScanBus.lastError.value = "Permesso posizione mancante"
            return
        }
        if (results.isEmpty()) return
        val newest = results.maxOf { it.timestamp }
        if (!updated || newest <= lastNewestTimestampUs) {
            // Android handed us cached results: the scan was throttled.
            ScanBus.staleScans.value = ScanBus.staleScans.value + 1
            ScanBus.throttled.value = true
            return
        }
        lastNewestTimestampUs = newest
        val nowMs = System.currentTimeMillis()
        val bootMs = SystemClock.elapsedRealtime()
        // Keep only results that belong to this scan (within the last few seconds).
        val entries = results.filter { bootMs - it.timestamp / 1000 < 15000 }.map { r ->
            val ssid = if (Build.VERSION.SDK_INT >= 33) {
                r.wifiSsid?.toString()?.trim('"') ?: ""
            } else r.SSID ?: ""
            ScanEntry(
                bssid = r.BSSID?.lowercase() ?: "",
                ssid = ssid,
                rssi = r.level,
                freqMhz = r.frequency,
                widthMhz = Channels.widthFromAndroid(r.channelWidth),
                centerFreq0 = r.centerFreq0,
                capabilities = r.capabilities ?: ""
            )
        }.filter { it.bssid.isNotEmpty() }
        val scan = Scan(t = nowMs, entries = entries)
        ScanBus.lastScan.value = scan
        ScanBus.totalScans.value = ScanBus.totalScans.value + 1
        ScanBus.scans.tryEmit(scan)
        updateNotification(entries.size)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String = getString(R.string.notif_scanning)): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, ProjectsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WifiScanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wifi)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }

    private fun updateNotification(count: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(getString(R.string.notif_last_scan, count)))
    }

    override fun onDestroy() {
        stopScanning()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
