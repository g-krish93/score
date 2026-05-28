/// Where the broadcast RTMP ingest is configured.
enum StreamDestination {
  /// Paste RTMP URL + key (YouTube Studio, Twitch dashboard, etc.).
  custom,

  /// Club YouTube via server OAuth.
  youtube,

  /// Club Twitch via server OAuth.
  twitch,
}
