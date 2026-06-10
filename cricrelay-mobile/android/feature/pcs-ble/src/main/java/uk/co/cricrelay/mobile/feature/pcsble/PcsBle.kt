package uk.co.cricrelay.mobile.feature.pcsble

import java.util.UUID

/** Optional PCS preset (from buildyourownscoreboard / community — verify on your kit). */
object PcsBle {
    const val PRESET_SERVICE_UUID = "5a0d6a15-b664-4304-8530-3a0ec53e5bc1"
    const val PRESET_CHAR_UUID = "df531f62-fc0b-40ce-81b2-32a6262ea440"
    const val PRESET_ADVERTISE_NAME = "BT-Scoreboard"

    val presetServiceUuid: UUID = UUID.fromString(PRESET_SERVICE_UUID)
    val presetCharUuid: UUID = UUID.fromString(PRESET_CHAR_UUID)
    val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val MODE_SCAN_ALL = "scan_all"
    const val MODE_SCAN_PCS_PRESET = "scan_pcs_preset"
    const val MODE_ADVERTISE_BOARD = "advertise_board"
}
