package uk.co.cricrelay.mobile.feature.studio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioCameraGateTest {

    @Test
    fun `prepare requires match permissions and surface`() {
        assertFalse(
            StudioCameraGate.canPrepareCamera(
                StudioCameraGate.Readiness(
                    matchLoaded = true,
                    permissionsGranted = false,
                    previewSurfaceBound = true,
                ),
            ),
        )
        assertFalse(
            StudioCameraGate.canPrepareCamera(
                StudioCameraGate.Readiness(
                    matchLoaded = true,
                    permissionsGranted = true,
                    previewSurfaceBound = false,
                ),
            ),
        )
        assertTrue(
            StudioCameraGate.canPrepareCamera(
                StudioCameraGate.Readiness(
                    matchLoaded = true,
                    permissionsGranted = true,
                    previewSurfaceBound = true,
                ),
            ),
        )
    }

    @Test
    fun `permissions require camera and microphone`() {
        assertFalse(StudioCameraGate.permissionsSatisfied(cameraGranted = true, audioGranted = false))
        assertTrue(StudioCameraGate.permissionsSatisfied(cameraGranted = true, audioGranted = true))
    }

    @Test
    fun `loading overlay stays transparent once match is available`() {
        assertTrue(
            StudioCameraGate.shouldShowOpaqueLoadingOverlay(
                loading = true,
                matchLoaded = false,
                error = null,
            ),
        )
        assertFalse(
            StudioCameraGate.shouldShowOpaqueLoadingOverlay(
                loading = true,
                matchLoaded = true,
                error = null,
            ),
        )
    }
}
