package ru.oasispay.data.ble

/** A per-advertiser signal filter. RSSI cannot establish a physical distance. */
internal class NearbyGate {
    private val strong = ArrayDeque<Long>()
    fun observe(rssi: Int, timeMs: Long): Boolean {
        if (rssi !in -45..-1 || (strong.isNotEmpty() && timeMs <= strong.last())) {
            strong.clear()
            return false
        }
        while (strong.isNotEmpty() && timeMs - strong.first() > 1200) strong.removeFirst()
        strong.addLast(timeMs)
        return strong.size >= 3 && timeMs - strong.first() >= 180
    }
}
