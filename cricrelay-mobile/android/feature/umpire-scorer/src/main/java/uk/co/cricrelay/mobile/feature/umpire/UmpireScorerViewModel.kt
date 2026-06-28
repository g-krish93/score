package uk.co.cricrelay.mobile.feature.umpire

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import uk.co.cricrelay.shared.model.UmpireBallEvent
import uk.co.cricrelay.shared.model.UmpireDeliveryMode
import uk.co.cricrelay.shared.model.UmpireScorerState
import javax.inject.Inject

@HiltViewModel
class UmpireScorerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(UmpireScorerState())
    val uiState: StateFlow<UmpireScorerState> = _state.asStateFlow()

    private val _newOverEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val newOverEvent: SharedFlow<Unit> = _newOverEvent.asSharedFlow()

    private val history = ArrayDeque<UmpireScorerState>()

    init {
        loadState()
    }

    fun onRunsPressed(runs: Int) {
        pushHistory()
        val s = _state.value
        val isLegal = s.deliveryMode == UmpireDeliveryMode.NORMAL ||
                s.deliveryMode == UmpireDeliveryMode.BYE ||
                s.deliveryMode == UmpireDeliveryMode.LEGBYE
        val runsScored = if (s.deliveryMode == UmpireDeliveryMode.WIDE ||
            s.deliveryMode == UmpireDeliveryMode.NOBALL) runs + 1 else runs
        val ball = UmpireBallEvent(
            label = buildLabel(s.deliveryMode, s.pendingWicket, runs),
            totalRuns = runsScored,
            isLegal = isLegal,
            isWicket = s.pendingWicket,
        )
        val newLegal = s.legalBallCount + if (isLegal) 1 else 0
        _state.value = s.copy(
            totalRuns = s.totalRuns + runsScored,
            totalWickets = s.totalWickets + if (s.pendingWicket) 1 else 0,
            currentOverBalls = s.currentOverBalls + ball,
            legalBallCount = newLegal,
            deliveryMode = UmpireDeliveryMode.NORMAL,
            pendingWicket = false,
        )
        saveState()
        if (newLegal >= 6) _newOverEvent.tryEmit(Unit)
    }

    fun onWidePressed() {
        val s = _state.value
        _state.value = s.copy(
            deliveryMode = if (s.deliveryMode == UmpireDeliveryMode.WIDE) UmpireDeliveryMode.NORMAL else UmpireDeliveryMode.WIDE,
        )
    }

    fun onNoBallPressed() {
        val s = _state.value
        _state.value = s.copy(
            deliveryMode = if (s.deliveryMode == UmpireDeliveryMode.NOBALL) UmpireDeliveryMode.NORMAL else UmpireDeliveryMode.NOBALL,
        )
    }

    fun onByePressed() {
        val s = _state.value
        _state.value = s.copy(
            deliveryMode = if (s.deliveryMode == UmpireDeliveryMode.BYE) UmpireDeliveryMode.NORMAL else UmpireDeliveryMode.BYE,
        )
    }

    fun onLegByePressed() {
        val s = _state.value
        _state.value = s.copy(
            deliveryMode = if (s.deliveryMode == UmpireDeliveryMode.LEGBYE) UmpireDeliveryMode.NORMAL else UmpireDeliveryMode.LEGBYE,
        )
    }

    fun onWicketToggle() {
        val s = _state.value
        _state.value = s.copy(pendingWicket = !s.pendingWicket)
    }

    fun onUndo() {
        val prev = history.removeLastOrNull() ?: return
        _state.value = prev
        saveState()
    }

    fun onEndOver() {
        pushHistory()
        val s = _state.value
        _state.value = s.copy(
            completedOvers = s.completedOvers + 1,
            currentOverBalls = emptyList(),
            legalBallCount = 0,
            deliveryMode = UmpireDeliveryMode.NORMAL,
            pendingWicket = false,
        )
        saveState()
    }

    fun onReset() {
        history.clear()
        _state.value = UmpireScorerState()
        saveState()
    }

    private fun pushHistory() {
        history.addLast(_state.value)
        if (history.size > 50) history.removeFirst()
    }

    private fun buildLabel(mode: UmpireDeliveryMode, wicket: Boolean, runs: Int): String {
        val base = when (mode) {
            UmpireDeliveryMode.NORMAL -> if (runs == 0) "·" else "$runs"
            UmpireDeliveryMode.WIDE -> if (runs == 0) "Wd" else "Wd+$runs"
            UmpireDeliveryMode.NOBALL -> if (runs == 0) "Nb" else "Nb+$runs"
            UmpireDeliveryMode.BYE -> if (runs == 0) "B" else "B+$runs"
            UmpireDeliveryMode.LEGBYE -> if (runs == 0) "Lb" else "Lb+$runs"
        }
        return if (wicket) "$base+W" else base
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun saveState() {
        val s = _state.value
        val prefs = context.getSharedPreferences("umpire_scorer", Context.MODE_PRIVATE)
        val ballsArray = JSONArray().apply {
            s.currentOverBalls.forEach { ball ->
                put(JSONObject().apply {
                    put("label", ball.label)
                    put("totalRuns", ball.totalRuns)
                    put("isLegal", ball.isLegal)
                    put("isWicket", ball.isWicket)
                })
            }
        }
        prefs.edit().apply {
            putInt("totalRuns", s.totalRuns)
            putInt("totalWickets", s.totalWickets)
            putInt("completedOvers", s.completedOvers)
            putInt("legalBallCount", s.legalBallCount)
            putString("currentOverBalls", ballsArray.toString())
        }.apply()
    }

    private fun loadState() {
        val prefs = context.getSharedPreferences("umpire_scorer", Context.MODE_PRIVATE)
        if (!prefs.contains("totalRuns")) return
        val balls = mutableListOf<UmpireBallEvent>()
        runCatching {
            val arr = JSONArray(prefs.getString("currentOverBalls", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                balls.add(
                    UmpireBallEvent(
                        label = obj.getString("label"),
                        totalRuns = obj.getInt("totalRuns"),
                        isLegal = obj.getBoolean("isLegal"),
                        isWicket = obj.getBoolean("isWicket"),
                    ),
                )
            }
        }
        _state.value = UmpireScorerState(
            totalRuns = prefs.getInt("totalRuns", 0),
            totalWickets = prefs.getInt("totalWickets", 0),
            completedOvers = prefs.getInt("completedOvers", 0),
            legalBallCount = prefs.getInt("legalBallCount", 0),
            currentOverBalls = balls,
        )
    }
}
