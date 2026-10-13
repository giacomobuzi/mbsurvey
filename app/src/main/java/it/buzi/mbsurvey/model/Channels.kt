package it.buzi.mbsurvey.model

enum class Band(val label: String) { B24("2.4 GHz"), B5("5 GHz"), B6("6 GHz"), UNKNOWN("?") }

object Channels {
    fun bandOf(freqMhz: Int): Band = when {
        freqMhz in 2400..2500 -> Band.B24
        freqMhz in 5150..5925 -> Band.B5
        freqMhz in 5925..7125 -> Band.B6
        else -> Band.UNKNOWN
    }

    fun channelOf(freqMhz: Int): Int = when {
        freqMhz == 2484 -> 14
        freqMhz in 2412..2472 -> (freqMhz - 2407) / 5
        freqMhz in 5150..5925 -> (freqMhz - 5000) / 5
        freqMhz in 5925..7125 -> (freqMhz - 5950) / 5
        else -> 0
    }

    /** Lower/upper edge of the channel in MHz given centre (or centerFreq0 for wide channels). */
    fun edges(centerMhz: Int, widthMhz: Int): Pair<Int, Int> {
        val half = widthMhz / 2
        return Pair(centerMhz - half, centerMhz + half)
    }

    /** True when two channels overlap in spectrum. */
    fun overlaps(c1: Int, w1: Int, c2: Int, w2: Int): Boolean {
        val (a1, b1) = edges(c1, w1)
        val (a2, b2) = edges(c2, w2)
        return a1 < b2 && a2 < b1
    }

    fun widthFromAndroid(channelWidth: Int): Int = when (channelWidth) {
        0 -> 20
        1 -> 40
        2 -> 80
        3 -> 160
        4 -> 160
        5 -> 320
        else -> 20
    }

    fun security(capabilities: String): String = when {
        capabilities.contains("WPA3") || capabilities.contains("SAE") -> "WPA3"
        capabilities.contains("WPA2") || capabilities.contains("RSN") -> "WPA2"
        capabilities.contains("WPA") -> "WPA"
        capabilities.contains("WEP") -> "WEP"
        capabilities.contains("OWE") -> "OWE"
        else -> "Open"
    }
}
