package uk.co.cricrelay.shared.repository

import uk.co.cricrelay.shared.api.CricRelayApiClient

interface ApiClientProvider {
    suspend fun get(): CricRelayApiClient
}
