package uk.co.cricrelay.mobile.feature.studio

/**
 * Gates [StudioViewModel.prepareCamera] until match, permissions, and GL surface are ready.
 */
object StudioCameraGate {

    data class Readiness(
        val matchLoaded: Boolean,
        val permissionsGranted: Boolean,
        val previewSurfaceBound: Boolean,
    )

    fun canPrepareCamera(readiness: Readiness): Boolean =
        readiness.matchLoaded && readiness.permissionsGranted && readiness.previewSurfaceBound

    fun permissionsSatisfied(cameraGranted: Boolean, audioGranted: Boolean): Boolean =
        cameraGranted && audioGranted

    /** Loading UI should not paint an opaque layer over the camera preview host. */
    fun shouldShowOpaqueLoadingOverlay(loading: Boolean, matchLoaded: Boolean, error: String?): Boolean =
        loading && !matchLoaded && error == null
}
