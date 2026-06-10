package uk.co.cricrelay.shared.repository

import uk.co.cricrelay.shared.api.CricRelayApiClient

class DefaultApiClientProvider(
    private val authRepository: AuthRepository,
) : ApiClientProvider {
    override suspend fun get(): CricRelayApiClient = authRepository.loadApiClient()
}
