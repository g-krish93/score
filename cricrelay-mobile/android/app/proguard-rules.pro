# CricRelay reflects into RootEncoder 2.4.8 internals (Camera2Controls) for correct
# tap-to-focus + cinematic (PREVIEW_STABILIZATION) stabilization. R8 must not rename
# these members or the reflection silently falls back to RootEncoder's weaker paths.
-keepclassmembers class com.pedro.encoder.input.video.Camera2ApiManager {
    android.hardware.camera2.CaptureRequest$Builder builderInputSurface;
    android.hardware.camera2.CameraCaptureSession cameraCaptureSession;
    android.os.Handler cameraHandler;
}
-keepclassmembers class com.pedro.library.base.Camera2Base {
    com.pedro.encoder.input.video.Camera2ApiManager cameraManager;
}
