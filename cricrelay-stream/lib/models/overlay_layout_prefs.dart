/// On-phone overlay frame + server scoreboard styling (size/theme).
class OverlayLayoutPrefs {
  const OverlayLayoutPrefs({
    this.size = 3,
    this.theme = 'classic',
    this.density = 'expanded',
    this.heightFraction = 0.22,
    this.widthFraction = 0.88,
    this.anchorX = 0.5,
    this.anchorY = 0.85,
    this.bottomMargin = 8,
    this.horizontalInset = 8,
    this.keepScreenOn = false,
    this.videoStabilization = true,
  });

  /// Cricket default: wide strip along bottom in landscape.
  static const cricketLandscape = OverlayLayoutPrefs(
    size: 3,
    heightFraction: 0.20,
    widthFraction: 0.92,
    anchorX: 0.5,
    anchorY: 0.88,
    bottomMargin: 8,
    horizontalInset: 8,
    videoStabilization: true,
  );

  /// Server overlay preset 1 (smallest) … 5 (largest).
  final int size;
  final String theme;
  final String density;
  /// Fraction of preview height for the scoreboard strip (0.12–0.45).
  final double heightFraction;
  /// Fraction of preview width for overlay frame (0.25–0.95).
  final double widthFraction;
  /// Normalized horizontal center (0 = left, 1 = right).
  final double anchorX;
  /// Normalized vertical center (0 = top, 1 = bottom).
  final double anchorY;
  final double bottomMargin;
  final double horizontalInset;
  /// If false, phone may sleep; Android capture service keeps CPU awake.
  final bool keepScreenOn;
  /// Electronic image stabilization (EIS) when supported on device.
  final bool videoStabilization;

  OverlayLayoutPrefs copyWith({
    int? size,
    String? theme,
    String? density,
    double? heightFraction,
    double? widthFraction,
    double? anchorX,
    double? anchorY,
    double? bottomMargin,
    double? horizontalInset,
    bool? keepScreenOn,
    bool? videoStabilization,
  }) {
    return OverlayLayoutPrefs(
      size: size ?? this.size,
      theme: theme ?? this.theme,
      density: density ?? this.density,
      heightFraction: heightFraction ?? this.heightFraction,
      widthFraction: widthFraction ?? this.widthFraction,
      anchorX: anchorX ?? this.anchorX,
      anchorY: anchorY ?? this.anchorY,
      bottomMargin: bottomMargin ?? this.bottomMargin,
      horizontalInset: horizontalInset ?? this.horizontalInset,
      keepScreenOn: keepScreenOn ?? this.keepScreenOn,
      videoStabilization: videoStabilization ?? this.videoStabilization,
    );
  }

  static OverlayLayoutPrefs fromJson(Map<String, dynamic> j) {
    return OverlayLayoutPrefs(
      size: (int.tryParse('${j['overlay_size']}') ?? 3).clamp(1, 5),
      theme: (j['theme'] ?? 'classic').toString(),
      density: (j['overlay_density'] ?? 'expanded').toString(),
      heightFraction: (double.tryParse('${j['height_fraction']}') ?? 0.22).clamp(0.12, 0.45),
      widthFraction: (double.tryParse('${j['width_fraction']}') ?? 0.88).clamp(0.25, 0.95),
      anchorX: (double.tryParse('${j['anchor_x']}') ?? 0.5).clamp(0.05, 0.95),
      anchorY: (double.tryParse('${j['anchor_y']}') ?? 0.85).clamp(0.05, 0.95),
      bottomMargin: (double.tryParse('${j['bottom_margin']}') ?? 8).clamp(0, 48),
      horizontalInset: (double.tryParse('${j['horizontal_inset']}') ?? 8).clamp(0, 80),
      keepScreenOn: j['keep_screen_on'] == true,
      videoStabilization: j['video_stabilization'] != false,
    );
  }

  Map<String, dynamic> toServerJson() => {
        'overlay_size': size,
        'theme': theme,
        'overlay_density': density,
      };

  /// Bottom-anchored scoreboard strip rect (cricket-style along lower edge).
  ({double left, double top, double width, double height}) frameRect(double maxW, double maxH) {
    final w = (maxW * widthFraction).clamp(80.0, maxW);
    final h = (maxH * heightFraction).clamp(48.0, maxH * 0.55);
    final inset = horizontalInset.clamp(0.0, maxW * 0.3);
    final left = (anchorX * maxW - w / 2).clamp(inset, maxW - w - inset);
    final margin = bottomMargin.clamp(0.0, 48.0);
    final top = (maxH - h - margin).clamp(0.0, maxH - h);
    return (left: left, top: top, width: w, height: h);
  }

  /// Reset strip to a visible bottom position (fixes stale drag data from older builds).
  OverlayLayoutPrefs withVisibleBottomStrip({required bool landscape}) {
    final base = landscape ? cricketLandscape : const OverlayLayoutPrefs(
      heightFraction: 0.18,
      widthFraction: 0.92,
      anchorX: 0.5,
      bottomMargin: 12,
      horizontalInset: 8,
    );
    return base.copyWith(
      size: size,
      theme: theme,
      density: density,
      keepScreenOn: keepScreenOn,
      videoStabilization: videoStabilization,
    );
  }

  /// True when saved layout likely used legacy center-anchor coords (overlay off-screen).
  bool get needsBottomStripReset =>
      bottomMargin > 48 || anchorY < 0.55;

  Map<String, dynamic> toLocalJson() => {
        ...toServerJson(),
        'height_fraction': heightFraction,
        'width_fraction': widthFraction,
        'anchor_x': anchorX,
        'anchor_y': anchorY,
        'bottom_margin': bottomMargin,
        'horizontal_inset': horizontalInset,
        'keep_screen_on': keepScreenOn,
        'video_stabilization': videoStabilization,
      };
}
