package uk.co.cricrelay.stream

/**
 * Decides where the live encoder renders as the app moves through lifecycle states.
 *
 * The camera feeds a GL interface; that interface is normally the on-screen [com.pedro.library.view.OpenGlView]
 * (a SurfaceView). When the surface goes away (screen lock, navigated away, app backgrounded WITHOUT
 * Picture-in-Picture) the SurfaceView's GL thread stops and the encoder starves — the broadcast freezes.
 *
 * The fix is to swap the encoder to RootEncoder's offscreen [com.pedro.library.view.GlStreamInterface]
 * (`replaceView(context)`) whenever no real surface is available, and swap back to the view
 * (`replaceView(openGlView)`) once one is. PiP keeps a real, visible surface, so it stays OnView.
 *
 * Pure logic so it can be unit-tested without a camera (mirrors [StreamOverlayPolicy]).
 */
object StreamLifecyclePolicy {

    enum class RenderTarget {
        /** Encode from the on-screen SurfaceView (preview visible, incl. while in PiP). */
        OnView,

        /** Encode from the offscreen GL interface (no surface; keeps the stream alive). */
        Offscreen,
    }

    /** While streaming, choose the render target. PiP is a visible surface, so it is OnView. */
    fun renderTarget(surfacePresent: Boolean, inPip: Boolean): RenderTarget =
        if (surfacePresent || inPip) RenderTarget.OnView else RenderTarget.Offscreen

    /** Flip the live encoder to offscreen GL (surface lost while we still need to broadcast). */
    fun shouldEnterBackground(isStreaming: Boolean, surfacePresent: Boolean, inPip: Boolean): Boolean =
        isStreaming && !surfacePresent && !inPip

    /** Restore the live encoder to the on-screen view once a valid surface is back. */
    fun shouldExitBackground(isStreaming: Boolean, backgroundRendering: Boolean, surfacePresent: Boolean): Boolean =
        isStreaming && backgroundRendering && surfacePresent

    /**
     * Gate the swap BACK to the on-screen view on the display actually compositing it.
     *
     * A "valid" surface is not enough: the lockscreen rotation (and AOD blips) deliver
     * surfaceChanged with a technically valid surface while the display is dark. Accepting
     * those swaps flaps the encoder between offscreen and on-view every few seconds of a
     * lock — each replaceView closes and reopens the camera + EGL context, and every churn
     * injects garbage frames into the live broadcast (field report: "90s TV static" bursts
     * while the screen is off). Screen off or keyguard showing ⇒ stay parked offscreen;
     * MainActivity.onStart re-triggers the restore after unlock.
     */
    fun shouldRestoreOnView(isInteractive: Boolean, keyguardLocked: Boolean): Boolean =
        isInteractive && !keyguardLocked

    /** Auto-enter Picture-in-Picture when the operator leaves the app mid-broadcast. */
    fun shouldEnterPipOnLeave(isStreaming: Boolean, pipSupported: Boolean): Boolean =
        isStreaming && pipSupported
}
