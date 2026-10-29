package dev.eltermino.ui

import dev.eltermino.core.data.Host
import kotlin.test.Test
import kotlin.test.assertEquals

class RecentHostsTest {
    private fun host(label: String, lastConnected: Long? = null, favorite: Boolean = false) =
        Host(id = label, label = label, address = "$label.local", lastConnectedEpochSeconds = lastConnected, favorite = favorite)

    /** The "+" menu crashed once some hosts had been connected and others never had. */
    @Test
    fun mixesHostsNeverConnectedWithRecentOnes() {
        val hosts = listOf(host("mai"), host("ieri", 1_800_000_000), host("preferito", favorite = true), host("oggi", 1_800_086_400))
        assertEquals(listOf("oggi", "ieri", "preferito", "mai"), recentHosts(hosts).map { it.label })
    }

    @Test
    fun keepsTheFirstEight() {
        val hosts = (1..12).map { host("h$it", lastConnected = it.toLong()) }
        assertEquals((12 downTo 5).map { "h$it" }, recentHosts(hosts).map { it.label })
    }
}
