package uk.co.cricrelay.mobile.feature.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds a pending `cricrelay://pair?…` URI from the system camera / QR scanner
 * until [RemoteControlViewModel] can redeem it.
 */
object PairDeepLinkBus {
    private val _pendingUri = MutableStateFlow<String?>(null)
    val pendingUri: StateFlow<String?> = _pendingUri.asStateFlow()

    fun offer(uri: String) {
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) return
        _pendingUri.value = trimmed
    }

    /** Returns and clears the pending URI, if any. */
    fun consume(): String? {
        val current = _pendingUri.value ?: return null
        _pendingUri.value = null
        return current
    }

    fun peek(): String? = _pendingUri.value
}
