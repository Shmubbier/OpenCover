package dev.opencover.phone

import dev.opencover.quick.ShizukuShell

/**
 * 5G on or off for the first SIM, through Shizuku's `cmd phone`. Searching for and holding a weak 5G signal costs more
 * battery than LTE. The command shows and takes the allowed types as names / a binary bitmask (bit = network type - 1).
 */
object Radio {
    private val TYPES = mapOf(
        "GPRS" to 1, "EDGE" to 2, "UMTS" to 3, "CDMA" to 4, "EVDO_0" to 5, "EVDO_A" to 6, "1xRTT" to 7, "HSDPA" to 8,
        "HSUPA" to 9, "HSPA" to 10, "IDEN" to 11, "EVDO_B" to 12, "LTE" to 13, "EHRPD" to 14, "HSPA+" to 15, "GSM" to 16,
        "TD_SCDMA" to 17, "IWLAN" to 18, "LTE_CA" to 19, "NR" to 20,
    )

    private fun allowed(): List<String>? =
        ShizukuShell.output("cmd", "phone", "get-allowed-network-types-for-users", "-s", "0")?.trim()?.split('|')?.filter { it.isNotBlank() }

    /** Whether 5G is allowed; null if it can't be read (no Shizuku). Blocking. */
    fun fiveG(): Boolean? = allowed()?.contains("NR")

    /** Allows or removes 5G, keeping every other type as it is. Blocking. */
    fun setFiveG(on: Boolean): Boolean {
        val now = allowed() ?: return false
        val types = now.map { TYPES[it] ?: return false }.toMutableSet() // an unknown name: leave the radio alone
        if (on) types += 20 else types -= 20
        val mask = types.fold(0L) { m, t -> m or (1L shl (t - 1)) }
        return ShizukuShell.run("cmd", "phone", "set-allowed-network-types-for-users", "-s", "0", java.lang.Long.toBinaryString(mask))
    }
}
