package io.github.kdroidfilter.nucleus.systeminfo.model

data class ConnectivityInfo(
    val isConnected: Boolean,
    val meteredStatus: MeteredStatus,
)

enum class MeteredStatus {
    UNKNOWN,
    UNMETERED,
    METERED,
}
