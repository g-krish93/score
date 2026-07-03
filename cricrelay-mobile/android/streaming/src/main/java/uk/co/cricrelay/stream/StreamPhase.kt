package uk.co.cricrelay.stream

/**
 * The broadcast session's phase — the single source of truth the engine's legacy state
 * booleans derive from. `paused` and `background` are orthogonal, but only exist while
 * live, so states like "background rendering with no stream" are unrepresentable.
 */
sealed interface StreamPhase {
    /** No encoder configured. */
    data object Idle : StreamPhase

    /** Encoder prepared (preview may be running or briefly restarting). */
    data object Prepared : StreamPhase

    /** RTMP session active; [background] = rendering on the offscreen GL interface. */
    data class Live(val paused: Boolean = false, val background: Boolean = false) : StreamPhase
}

/**
 * Valid phase transitions. The engine dispatches intents at every point it used to flip a
 * state boolean; a refused intent (null) means the caller is acting on stale state — the
 * engine logs it and the phase (and everything derived from it) stays consistent.
 *
 * Golden-path guards encoded here:
 * - [Intent.Prepare]/[Intent.Release] are refused while live — a mid-stream encoder
 *   re-prepare is the known hard crash, and a surface-loss callback racing the
 *   background swap must not tear down the prepared pipeline (the old
 *   `encoderPrepared = false` double-call bug).
 * - Pause/resume and background/foreground only exist within Live and preserve each
 *   other's flag.
 */
object StreamPhasePolicy {
    enum class Intent { Prepare, Release, GoLive, Stop, Pause, Resume, EnterBackground, ExitBackground }

    /** Next phase for [intent] in [phase], or null when the transition is invalid. */
    fun next(phase: StreamPhase, intent: Intent): StreamPhase? = when (intent) {
        Intent.Prepare -> if (phase is StreamPhase.Live) null else StreamPhase.Prepared
        Intent.Release -> if (phase is StreamPhase.Live) null else StreamPhase.Idle
        Intent.GoLive -> if (phase == StreamPhase.Prepared) StreamPhase.Live() else null
        // Stop is the one always-legal way out of Live; idempotent when already down.
        Intent.Stop -> if (phase is StreamPhase.Live) StreamPhase.Idle else phase
        Intent.Pause -> (phase as? StreamPhase.Live)?.copy(paused = true)
        Intent.Resume -> (phase as? StreamPhase.Live)?.copy(paused = false)
        Intent.EnterBackground -> (phase as? StreamPhase.Live)?.copy(background = true)
        Intent.ExitBackground -> (phase as? StreamPhase.Live)?.copy(background = false)
    }
}
