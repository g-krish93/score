package uk.co.cricrelay.mobile

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide signal that the stored session died (a main-token request returned 401).
 * Emitted from the shared AuthRepository's onSessionExpired callback (any thread, so
 * tryEmit with a buffer); MainActivity clears the persisted token and the nav host
 * bounces to the login screen — parity with iOS SessionViewModel.sessionDidExpire.
 */
@Singleton
class SessionEvents @Inject constructor() {
    private val _expired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val expired: SharedFlow<Unit> = _expired.asSharedFlow()

    fun signalExpired() {
        _expired.tryEmit(Unit)
    }
}
