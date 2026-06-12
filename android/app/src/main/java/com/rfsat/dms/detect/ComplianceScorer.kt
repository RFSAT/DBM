package com.rfsat.dms.detect

import com.rfsat.dms.ComplianceState
import com.rfsat.dms.RiskEventCandidate
import com.rfsat.dms.RiskType
import com.rfsat.dms.Severity
import com.rfsat.dms.SpeedSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Driver-compliance scoring, 0..100, combining behavioural risk events and
 * road-regulation compliance.
 *
 * Model:
 *  - score starts at 100 per trip;
 *  - each accepted event deducts RiskType.scorePenalty, scaled by severity
 *    (CRITICAL x1.0, WARNING x0.6, INFO x0.25);
 *  - continuous SPEEDING is evaluated here (GNSS speed vs active posted
 *    limit, with tolerance) and raised as its own event;
 *  - good behaviour recovers the score by +1 per clean minute, capped at 100;
 *  - the per-type deduction is rate-limited so a persisting condition
 *    degrades the score steadily, not instantaneously to zero.
 */
class ComplianceScorer {

    private val _state = MutableStateFlow(ComplianceState())
    val state: StateFlow<ComplianceState> = _state

    private var scoreF = 100f
    private var lastRecoveryMs = System.currentTimeMillis()
    private var lastSpeedEventMs = 0L
    private val lastDeductMs = HashMap<RiskType, Long>()

    fun onSpeedLimitSeen(limitKmh: Int) {
        _state.value = _state.value.copy(activeSpeedLimitKmh = limitKmh)
    }

    /** Call ~1 Hz with current speed and its source. May return a SPEEDING event. */
    fun onSpeed(speedKmh: Int, source: SpeedSource, tMs: Long): RiskEventCandidate? {
        _state.value = _state.value.copy(currentSpeedKmh = speedKmh, speedSource = source)
        // With only a visual estimate, widen the tolerance to reflect its accuracy.
        val tolerance = if (source == SpeedSource.VISUAL)
            SPEED_TOLERANCE_VISUAL_KMH else SPEED_TOLERANCE_KMH

        // clean-minute recovery
        if (tMs - lastRecoveryMs > 60_000) {
            lastRecoveryMs = tMs
            scoreF = (scoreF + 1f).coerceAtMost(100f)
            publish()
        }

        if (source == SpeedSource.NONE) return null
        val limit = _state.value.activeSpeedLimitKmh ?: return null
        val over = speedKmh - limit
        if (over <= tolerance) return null
        if (tMs - lastSpeedEventMs < SPEED_EVENT_INTERVAL_MS) return null
        lastSpeedEventMs = tMs
        return RiskEventCandidate(
            RiskType.SPEEDING,
            if (over > limit * 0.2f) Severity.CRITICAL else Severity.WARNING,
            if (source == SpeedSource.VISUAL) 0.6f else 0.9f,
            "$speedKmh km/h in a $limit zone" +
                if (source == SpeedSource.VISUAL) " (visually estimated)" else "")
    }

    /** Apply an accepted event's penalty to the score. */
    fun onEvent(ev: RiskEventCandidate, tMs: Long) {
        val last = lastDeductMs[ev.type] ?: 0L
        if (tMs - last < DEDUCT_INTERVAL_MS) return
        lastDeductMs[ev.type] = tMs
        val mult = when (ev.severity) {
            Severity.CRITICAL -> 1.0f; Severity.WARNING -> 0.6f; Severity.INFO -> 0.25f
        }
        scoreF = (scoreF - ev.type.scorePenalty * mult).coerceAtLeast(0f)
        _state.value = _state.value.copy(tripEvents = _state.value.tripEvents + 1)
        publish()
    }

    fun resetTrip() {
        scoreF = 100f
        _state.value = ComplianceState(
            activeSpeedLimitKmh = _state.value.activeSpeedLimitKmh)
        publish()
    }

    private fun publish() { _state.value = _state.value.copy(score = scoreF.toInt()) }

    companion object {
        const val SPEED_TOLERANCE_KMH = 3
        const val SPEED_TOLERANCE_VISUAL_KMH = 10
        const val SPEED_EVENT_INTERVAL_MS = 15_000L
        const val DEDUCT_INTERVAL_MS = 20_000L
    }
}
