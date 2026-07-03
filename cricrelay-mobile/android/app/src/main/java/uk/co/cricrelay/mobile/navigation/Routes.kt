package uk.co.cricrelay.mobile.navigation

import kotlinx.serialization.Serializable

@Serializable
object LoginRoute

@Serializable
object OnboardingRoute

@Serializable
object HomeRoute

@Serializable
data class CreateStreamRoute(val mode: String = "play_cricket")

@Serializable
data class StudioRoute(val matchSlug: String)

@Serializable
data class ScoringRoute(val matchSlug: String)

@Serializable
data class ScorerQrRoute(val matchSlug: String)

@Serializable
data class PairRemoteRoute(val matchSlug: String)

@Serializable
object RemoteControlRoute

@Serializable
object RegisterRoute
