package uk.co.cricrelay.umpire

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

data class BallEvent(
    val label: String,
    val totalRuns: Int,
    val isLegal: Boolean,
    val isWicket: Boolean,
)

enum class DeliveryMode { NORMAL, WIDE, NOBALL, BYE, LEGBYE }

class MainActivity : AppCompatActivity() {

    private var totalRuns = 0
    private var totalWickets = 0
    private var completedOvers = 0
    private val currentOverBalls = mutableListOf<BallEvent>()
    private var legalBallCount = 0

    private var deliveryMode = DeliveryMode.NORMAL
    private var pendingWicket = false

    private lateinit var scoreText: TextView
    private lateinit var overText: TextView
    private lateinit var overBallsLayout: LinearLayout
    private lateinit var modeIndicator: TextView
    private lateinit var wicketBtn: Button
    private lateinit var wideBtn: Button
    private lateinit var noballBtn: Button
    private lateinit var byeBtn: Button
    private lateinit var legbyeBtn: Button

    private val prefs by lazy { getSharedPreferences("umpire_scorer", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scoreText = findViewById(R.id.scoreText)
        overText = findViewById(R.id.overText)
        overBallsLayout = findViewById(R.id.overBallsLayout)
        modeIndicator = findViewById(R.id.modeIndicator)
        wicketBtn = findViewById(R.id.wicketBtn)
        wideBtn = findViewById(R.id.wideBtn)
        noballBtn = findViewById(R.id.noballBtn)
        byeBtn = findViewById(R.id.byeBtn)
        legbyeBtn = findViewById(R.id.legbyeBtn)

        val runValues = intArrayOf(0, 1, 2, 3, 4, 6)
        val runBtnIds = intArrayOf(R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4, R.id.btn6)
        for (i in runValues.indices) {
            findViewById<Button>(runBtnIds[i]).setOnClickListener { onRunsPressed(runValues[i]) }
        }

        wideBtn.setOnClickListener { onModeToggle(DeliveryMode.WIDE) }
        noballBtn.setOnClickListener { onModeToggle(DeliveryMode.NOBALL) }
        byeBtn.setOnClickListener { onModeToggle(DeliveryMode.BYE) }
        legbyeBtn.setOnClickListener { onModeToggle(DeliveryMode.LEGBYE) }
        wicketBtn.setOnClickListener { onWicketToggle() }

        findViewById<Button>(R.id.undoBtn).setOnClickListener { onUndo() }
        findViewById<Button>(R.id.newOverBtn).setOnClickListener { onNewOver() }
        findViewById<Button>(R.id.resetBtn).setOnClickListener { confirmReset() }

        loadState()
        updateUI()
    }

    // ── Delivery recording ────────────────────────────────────────────────────

    private fun onRunsPressed(runs: Int) {
        val ball = when (deliveryMode) {
            DeliveryMode.NORMAL -> {
                val label = buildString {
                    if (pendingWicket) append("W")
                    if (runs > 0 || !pendingWicket) append(if (runs == 0) "." else "$runs")
                }
                BallEvent(label, runs, isLegal = true, isWicket = pendingWicket)
            }
            DeliveryMode.WIDE -> {
                // 1 penalty + any runs scored running off the wide
                val label = if (runs == 0) "Wd" else "Wd+$runs"
                BallEvent(label, runs + 1, isLegal = false, isWicket = pendingWicket)
            }
            DeliveryMode.NOBALL -> {
                // 1 penalty + any runs scored off the bat/running
                val label = if (runs == 0) "Nb" else "Nb+$runs"
                BallEvent(label, runs + 1, isLegal = false, isWicket = pendingWicket)
            }
            DeliveryMode.BYE -> {
                val label = if (runs == 0) "." else "B$runs"
                BallEvent(label, runs, isLegal = true, isWicket = pendingWicket)
            }
            DeliveryMode.LEGBYE -> {
                val label = if (runs == 0) "." else "Lb$runs"
                BallEvent(label, runs, isLegal = true, isWicket = pendingWicket)
            }
        }
        recordBall(ball)
    }

    private fun onModeToggle(mode: DeliveryMode) {
        deliveryMode = if (deliveryMode == mode) DeliveryMode.NORMAL else mode
        updateModeButtons()
    }

    private fun onWicketToggle() {
        pendingWicket = !pendingWicket
        updateModeButtons()
    }

    private fun recordBall(ball: BallEvent) {
        currentOverBalls.add(ball)
        if (ball.isLegal) legalBallCount++
        totalRuns += ball.totalRuns
        if (ball.isWicket) totalWickets++

        deliveryMode = DeliveryMode.NORMAL
        pendingWicket = false

        saveState()
        updateUI()

        if (legalBallCount >= 6) {
            AlertDialog.Builder(this)
                .setTitle("Over Complete")
                .setMessage("Over ${completedOvers + 1} complete — start a new over?")
                .setPositiveButton("New Over") { _, _ -> endOver() }
                .setNegativeButton("Not Yet", null)
                .show()
        }
    }

    // ── Undo ──────────────────────────────────────────────────────────────────

    private fun onUndo() {
        if (currentOverBalls.isEmpty()) {
            toast("Nothing to undo")
            return
        }
        val last = currentOverBalls.removeLast()
        if (last.isLegal) legalBallCount--
        totalRuns -= last.totalRuns
        if (last.isWicket) totalWickets--
        deliveryMode = DeliveryMode.NORMAL
        pendingWicket = false
        saveState()
        updateUI()
    }

    // ── Over management ───────────────────────────────────────────────────────

    private fun onNewOver() {
        if (currentOverBalls.isEmpty()) {
            toast("No balls bowled yet")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("End Over?")
            .setMessage("End after $legalBallCount legal ball(s) in this over?")
            .setPositiveButton("End Over") { _, _ -> endOver() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun endOver() {
        completedOvers++
        currentOverBalls.clear()
        legalBallCount = 0
        deliveryMode = DeliveryMode.NORMAL
        pendingWicket = false
        saveState()
        updateUI()
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Reset Match?")
            .setMessage("This will clear all runs, wickets, and overs.")
            .setPositiveButton("Reset") { _, _ -> resetMatch() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetMatch() {
        totalRuns = 0
        totalWickets = 0
        completedOvers = 0
        currentOverBalls.clear()
        legalBallCount = 0
        deliveryMode = DeliveryMode.NORMAL
        pendingWicket = false
        saveState()
        updateUI()
    }

    // ── UI updates ────────────────────────────────────────────────────────────

    private fun updateUI() {
        scoreText.text = "$totalRuns/$totalWickets"
        overText.text = "Overs: $completedOvers.$legalBallCount"
        updateOverChips()
        updateModeButtons()
    }

    private fun updateOverChips() {
        overBallsLayout.removeAllViews()
        val dp4 = (4 * resources.displayMetrics.density).toInt()
        val dp8 = (8 * resources.displayMetrics.density).toInt()
        val dp28 = (28 * resources.displayMetrics.density).toInt()

        for (ball in currentOverBalls) {
            val chipColor = chipColorFor(ball)
            val tv = TextView(this).apply {
                text = ball.label
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
                setPadding(dp8, dp4, dp8, dp4)
                background = roundedBg(chipColor)
                val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp28)
                p.setMargins(dp4, 0, dp4, 0)
                layoutParams = p
            }
            overBallsLayout.addView(tv)
        }

        // Remaining legal ball slots
        val remaining = (6 - legalBallCount).coerceAtLeast(0)
        val emptyColor = ContextCompat.getColor(this, R.color.chip_empty)
        val emptyTextColor = ContextCompat.getColor(this, R.color.text_hint)
        repeat(remaining) {
            val tv = TextView(this).apply {
                text = "○"
                textSize = 13f
                setTextColor(emptyTextColor)
                setPadding(dp8, dp4, dp8, dp4)
                background = roundedBg(emptyColor)
                val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp28)
                p.setMargins(dp4, 0, dp4, 0)
                layoutParams = p
            }
            overBallsLayout.addView(tv)
        }
    }

    private fun chipColorFor(ball: BallEvent): Int {
        val res = when {
            ball.isWicket -> R.color.chip_wicket
            ball.label.startsWith("Wd") -> R.color.chip_wide
            ball.label.startsWith("Nb") -> R.color.chip_noball
            ball.label.startsWith("B") -> R.color.chip_bye
            ball.label.startsWith("Lb") -> R.color.chip_legbye
            ball.totalRuns >= 4 -> R.color.chip_boundary
            ball.totalRuns >= 1 -> R.color.chip_runs
            else -> R.color.chip_dot
        }
        return ContextCompat.getColor(this, res)
    }

    private fun roundedBg(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 8f * resources.displayMetrics.density
        setColor(color)
    }

    private fun updateModeButtons() {
        val extrasNormal = ContextCompat.getColor(this, R.color.extras_btn)
        val extrasActive = ContextCompat.getColor(this, R.color.extras_btn_active)
        val byeNormal = ContextCompat.getColor(this, R.color.bye_btn)
        val byeActive = ContextCompat.getColor(this, R.color.bye_btn_active)
        val wicketNormal = ContextCompat.getColor(this, R.color.wicket_btn)
        val wicketActive = ContextCompat.getColor(this, R.color.wicket_btn_active)

        wideBtn.backgroundTintList = colorStateList(
            if (deliveryMode == DeliveryMode.WIDE) extrasActive else extrasNormal,
        )
        noballBtn.backgroundTintList = colorStateList(
            if (deliveryMode == DeliveryMode.NOBALL) extrasActive else extrasNormal,
        )
        byeBtn.backgroundTintList = colorStateList(
            if (deliveryMode == DeliveryMode.BYE) byeActive else byeNormal,
        )
        legbyeBtn.backgroundTintList = colorStateList(
            if (deliveryMode == DeliveryMode.LEGBYE) byeActive else byeNormal,
        )
        wicketBtn.backgroundTintList = colorStateList(
            if (pendingWicket) wicketActive else wicketNormal,
        )

        val modeLabel = buildString {
            if (deliveryMode != DeliveryMode.NORMAL) {
                append(deliveryMode.name.lowercase().replaceFirstChar { it.uppercase() })
                append(" — now tap runs")
            }
            if (pendingWicket) {
                if (isNotEmpty()) append("  +  ")
                append("WICKET flagged — tap runs")
            }
            if (isEmpty()) append("Tap runs — or pick type first")
        }
        modeIndicator.text = modeLabel
    }

    private fun colorStateList(color: Int) =
        android.content.res.ColorStateList.valueOf(color)

    // ── State persistence ─────────────────────────────────────────────────────

    private fun saveState() {
        val balls = JSONArray()
        for (b in currentOverBalls) {
            balls.put(
                JSONObject()
                    .put("label", b.label)
                    .put("runs", b.totalRuns)
                    .put("legal", b.isLegal)
                    .put("wicket", b.isWicket),
            )
        }
        prefs.edit()
            .putInt("totalRuns", totalRuns)
            .putInt("totalWickets", totalWickets)
            .putInt("completedOvers", completedOvers)
            .putInt("legalBallCount", legalBallCount)
            .putString("currentOverBalls", balls.toString())
            .apply()
    }

    private fun loadState() {
        totalRuns = prefs.getInt("totalRuns", 0)
        totalWickets = prefs.getInt("totalWickets", 0)
        completedOvers = prefs.getInt("completedOvers", 0)
        legalBallCount = prefs.getInt("legalBallCount", 0)
        currentOverBalls.clear()
        try {
            val arr = JSONArray(prefs.getString("currentOverBalls", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                currentOverBalls.add(
                    BallEvent(
                        label = obj.getString("label"),
                        totalRuns = obj.getInt("runs"),
                        isLegal = obj.getBoolean("legal"),
                        isWicket = obj.getBoolean("wicket"),
                    ),
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
