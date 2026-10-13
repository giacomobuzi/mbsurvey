package it.buzi.mbsurvey.wifi

import it.buzi.mbsurvey.model.Scan
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-process bus between the scan service and the UI. */
object ScanBus {
    /** Every fresh scan result set. */
    val scans = MutableSharedFlow<Scan>(replay = 0, extraBufferCapacity = 64)

    /** Service state for the UI. */
    val running = MutableStateFlow(false)
    val lastScan = MutableStateFlow<Scan?>(null)
    val totalScans = MutableStateFlow(0)
    val staleScans = MutableStateFlow(0)
    val throttled = MutableStateFlow(false)
    val lastError = MutableStateFlow<String?>(null)
}
