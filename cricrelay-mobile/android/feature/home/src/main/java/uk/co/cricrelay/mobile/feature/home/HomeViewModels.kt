package uk.co.cricrelay.mobile.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uk.co.cricrelay.mobile.database.StreamDao
import uk.co.cricrelay.mobile.database.toDomain
import uk.co.cricrelay.mobile.database.toEntity
import uk.co.cricrelay.shared.model.FixtureItem
import uk.co.cricrelay.shared.model.PlatformStatus
import uk.co.cricrelay.shared.model.StreamMatch
import uk.co.cricrelay.shared.repository.AuthRepository
import uk.co.cricrelay.shared.repository.StreamRepository
import javax.inject.Inject

data class HomeUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val streams: List<StreamMatch> = emptyList(),
    val fixtures: List<FixtureItem> = emptyList(),
    val activeMatchIds: Set<String> = emptySet(),
    val slotsUsed: Int = 0,
    val slotsTotal: Int = 6,
    val error: String? = null,
    val showAdvanced: Boolean = false,
    val youtube: PlatformStatus = PlatformStatus(),
    val twitch: PlatformStatus = PlatformStatus(),
    val volunteerBannerDismissed: Boolean = false,
    val managementSlug: String? = null,
    val renameLabel: String = "",
    /** Linked Play-Cricket site: null until the first fixtures load, "" when none linked. */
    val clubSiteUrl: String? = null,
    val clubNudgeDismissed: Boolean = false,
    val clubSheet: Boolean = false,
    val clubInput: String = "",
    val clubSaving: Boolean = false,
    val clubError: String? = null,
)

data class CreateStreamUiState(
    val loading: Boolean = false,
    val fixtures: List<FixtureItem> = emptyList(),
    val activeMatchIds: Set<String> = emptySet(),
    val fixtureSourceUrl: String = "",
    val selectedMatchId: String = "",
    val label: String = "",
    val cricheroesUrl: String = "",
    val error: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val streamRepository: StreamRepository,
    private val authRepository: AuthRepository,
    private val streamDao: StreamDao,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        refresh(showFullLoading = true)
    }

    fun refresh(showFullLoading: Boolean = false) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    loading = showFullLoading && it.streams.isEmpty(),
                    refreshing = !showFullLoading,
                    error = null,
                )
            }
            try {
                val cached = streamDao.getAll().map { it.toDomain() }
                if (cached.isNotEmpty()) {
                    _uiState.update { it.copy(streams = cached, loading = false) }
                }
                val streams = streamRepository.listStreams()
                streamDao.clear()
                streamDao.upsertAll(streams.map { it.toEntity() })
                val fixtures = streamRepository.listFixtures()
                val youtube = runCatching { streamRepository.youtubePlatformStatus() }.getOrDefault(PlatformStatus())
                val twitch = runCatching { streamRepository.twitchPlatformStatus() }.getOrDefault(PlatformStatus())
                _uiState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        streams = streams,
                        fixtures = fixtures.fixtures,
                        activeMatchIds = fixtures.activeMatchIds,
                        slotsUsed = fixtures.slotsUsed,
                        slotsTotal = fixtures.slotsTotal,
                        clubSiteUrl = fixtures.fixtureSourceUrl,
                        youtube = youtube,
                        twitch = twitch,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = e.message?.replace("Exception: ", "").orEmpty().ifBlank { "Failed to load" },
                    )
                }
            }
        }
    }

    fun toggleAdvanced() = _uiState.update { it.copy(showAdvanced = !it.showAdvanced) }

    fun dismissVolunteerBanner() = _uiState.update { it.copy(volunteerBannerDismissed = true) }

    fun dismissClubNudge() = _uiState.update { it.copy(clubNudgeDismissed = true) }

    fun openClubSheet() = _uiState.update {
        it.copy(clubSheet = true, clubInput = it.clubSiteUrl.orEmpty(), clubError = null)
    }

    fun closeClubSheet() = _uiState.update { it.copy(clubSheet = false, clubSaving = false) }

    fun onClubInputChange(value: String) = _uiState.update { it.copy(clubInput = value, clubError = null) }

    fun saveClubLink() {
        val input = _uiState.value.clubInput.trim()
        if (input.isBlank()) {
            _uiState.update {
                it.copy(clubError = "Enter your club code — the short name before .play-cricket.com")
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(clubSaving = true, clubError = null) }
            try {
                val linked = authRepository.updateAccount(input)
                _uiState.update { it.copy(clubSaving = false, clubSheet = false, clubSiteUrl = linked) }
                refresh()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        clubSaving = false,
                        clubError = e.message?.replace("Exception: ", "").orEmpty()
                            .ifBlank { "Could not link the club site" },
                    )
                }
            }
        }
    }

    fun openManagement(slug: String, label: String) {
        _uiState.update { it.copy(managementSlug = slug, renameLabel = label) }
    }

    fun closeManagement() = _uiState.update { it.copy(managementSlug = null) }

    fun onRenameLabelChange(value: String) = _uiState.update { it.copy(renameLabel = value) }

    fun renameStream(onDone: () -> Unit) {
        val slug = _uiState.value.managementSlug ?: return
        val label = _uiState.value.renameLabel.trim()
        if (label.isBlank()) return
        viewModelScope.launch {
            try {
                streamRepository.renameStream(slug, label)
                closeManagement()
                refresh()
                onDone()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun deleteStream(slug: String) {
        viewModelScope.launch {
            try {
                streamRepository.deleteStream(slug)
                closeManagement()
                refresh()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            authRepository.logout()
            streamDao.clear()
            onLoggedOut()
        }
    }

    suspend fun youtubeAuthorizeUrl(): String = streamRepository.youtubeAuthorizeUrl()

    suspend fun twitchAuthorizeUrl(): String = streamRepository.twitchAuthorizeUrl()

    fun disconnectYoutube() {
        viewModelScope.launch {
            runCatching { streamRepository.youtubeDisconnect() }
            refresh()
        }
    }

    fun disconnectTwitch() {
        viewModelScope.launch {
            runCatching { streamRepository.twitchDisconnect() }
            refresh()
        }
    }
}

@HiltViewModel
class CreateStreamViewModel @Inject constructor(
    private val streamRepository: StreamRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CreateStreamUiState())
    val uiState: StateFlow<CreateStreamUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val fixtures = streamRepository.listFixtures()
                _uiState.update {
                    it.copy(
                        fixtures = fixtures.fixtures,
                        activeMatchIds = fixtures.activeMatchIds,
                        fixtureSourceUrl = fixtures.fixtureSourceUrl,
                        selectedMatchId = fixtures.fixtures.firstOrNull()?.matchId.orEmpty(),
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun onLabelChange(value: String) = _uiState.update { it.copy(label = value) }
    fun onMatchSelected(matchId: String) = _uiState.update { it.copy(selectedMatchId = matchId) }
    fun onCricheroesUrlChange(value: String) = _uiState.update { it.copy(cricheroesUrl = value) }

    fun createPlayCricket(onCreated: (StreamMatch) -> Unit) {
        val state = _uiState.value
        if (state.selectedMatchId.isBlank()) {
            _uiState.update { it.copy(error = "Select a fixture") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val match = streamRepository.createPlayCricketStream(state.selectedMatchId, state.label)
                onCreated(match)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, loading = false) }
            }
        }
    }

    fun createCricHeroes(onCreated: (StreamMatch) -> Unit) {
        val state = _uiState.value
        if (state.cricheroesUrl.isBlank()) {
            _uiState.update { it.copy(error = "Paste a CricHeroes scorecard URL") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val match = streamRepository.createCricHeroesStream(
                    matchUrl = state.cricheroesUrl.trim(),
                    label = state.label.ifBlank { "CricHeroes stream" },
                )
                onCreated(match)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, loading = false) }
            }
        }
    }

    fun createManual(onCreated: (StreamMatch) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val match = streamRepository.createManualStream(
                    label = _uiState.value.label.trim().ifBlank { "Manual stream" },
                )
                onCreated(match)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, loading = false) }
            }
        }
    }
}

fun streamStatusChips(stream: StreamMatch): List<uk.co.cricrelay.mobile.ui.StreamStatusChip> {
    val chips = mutableListOf<uk.co.cricrelay.mobile.ui.StreamStatusChip>()
    if (stream.broadcast.isStreaming) {
        chips += uk.co.cricrelay.mobile.ui.StreamStatusChip("ON AIR", uk.co.cricrelay.mobile.ui.AppColors.Live, pulse = true)
    } else if (stream.broadcast.isPaused) {
        chips += uk.co.cricrelay.mobile.ui.StreamStatusChip("BROADCAST PAUSED", uk.co.cricrelay.mobile.ui.AppColors.Warning)
    }
    if (stream.scoringStale) {
        chips += uk.co.cricrelay.mobile.ui.StreamStatusChip("SCORING STALE", uk.co.cricrelay.mobile.ui.AppColors.Warning)
    } else if (stream.scoringActive) {
        chips += uk.co.cricrelay.mobile.ui.StreamStatusChip("SCORING", uk.co.cricrelay.mobile.ui.AppColors.Success)
    }
    if (chips.size < 2 && stream.scoringMode == "manual") {
        chips += uk.co.cricrelay.mobile.ui.StreamStatusChip("MANUAL", uk.co.cricrelay.mobile.ui.AppColors.OnBackgroundMuted)
    } else if (chips.size < 2 && stream.scoringMode == "ble") {
        chips += uk.co.cricrelay.mobile.ui.StreamStatusChip("BLE", uk.co.cricrelay.mobile.ui.AppColors.OnBackgroundMuted)
    }
    return chips.take(2)
}
