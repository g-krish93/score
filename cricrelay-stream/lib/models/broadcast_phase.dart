/// Broadcast lifecycle — drives UI affordances and encoder policy.
enum BroadcastPhase {
  initializing,
  permissionDenied,
  cameraLoading,
  cameraError,
  previewReady,
  connecting,
  live,
  paused,
  stopped,
}

extension BroadcastPhaseX on BroadcastPhase {
  bool get isLivePhase => this == BroadcastPhase.live || this == BroadcastPhase.paused;

  bool get canGoLive =>
      this == BroadcastPhase.previewReady || this == BroadcastPhase.stopped;

  bool get showsPreviewOverlay => !isLivePhase;
}
