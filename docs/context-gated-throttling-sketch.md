# Context-Gated Throttling — Design Sketch

**Status:** design only, to implement after the speed-limit fusion is in place
(it depends on the on-device map being available).

## The principle

Naive "throttle when no events, full-rate when events" cannot work, because
*detecting* an event is the work — you can't know a frame is uneventful without
processing it. Throttling the event detectors to save heat means missing the
event you needed to catch (exactly the missed-sign problem fixed in v16.9).

So the workable principle is **context-gated throttling**: use *cheap, always-on
signals* to decide which *expensive* analyses are currently relevant, while
keeping the event-discovery detectors running fast enough to catch their events.

## Classification of the pipeline

### A. Keep at full rate always (event is silent / safety-critical)
- **Driver monitoring** (microsleep, gaze, PERCLOS). Drowsiness onset is exactly
  when the driver cannot signal "attend now." Never throttle on a "no events"
  basis.

### B. Throttle by MAP/GPS context (predictive — safe because it doesn't rely on
having already seen the event)
- **Sign detection.** Use the bundled OSM map + GPS to know what's ahead:
  - Near a junction, urban area, or a road segment that the map/cache says
    carries signs -> FULL rate.
  - On a long open segment with no upcoming features -> REDUCED rate.
  This is *predictive* surge, not idle throttle: the map tells you *where* signs
  are likely before you see them. It cuts heat on the long uneventful stretches
  (where drives overheated) while improving capture where it matters.

### C. Throttle by PRESENCE (cheap detector gates the expensive one)
- **Following-distance / collision math.** Run the cheap object detector
  continuously; only run the expensive distance/closing-speed pipeline when a
  vehicle is actually detected in-lane ahead. No lead car -> idle.
- **Traffic-light analysis.** Gate on junction context (map) — pointless on an
  open highway between intersections.

### D. Throttle by STABILITY (state changing slowly)
- **Lane tracking.** Full rate while lane position is drifting or changing;
  reduced when dead-centre and steady for several seconds, surging on drift.

## Proposed mechanism

A single `ProcessingGovernor` that each analyzer consults for its current
interval, combining three inputs:

```
fun intervalFor(role): Long {
    val base = baseInterval[role]
    val thermalMult = thermalFactor            // existing thermal backoff
    val contextMult = contextMultiplier(role)  // NEW: 1.0 (full) .. 4.0 (idle)
    return base * thermalMult * contextMult
}
```

`contextMultiplier(role)`:
- DRIVER -> always 1.0 (never gated down)
- SIGN   -> 1.0 if (map says feature ahead within N metres) || (recent sign seen)
            else 3.0
- FOLLOWING -> 1.0 if lead vehicle present else 4.0 (effectively idle)
- LANE   -> 1.0 if drifting else 2.0
- LIGHT  -> 1.0 if junction context else 4.0

Thermal and context multipliers COMBINE (multiply), so a hot phone on an open
road backs off the most, while a cool phone near a junction runs everything
full-rate. Driver monitoring is the floor — thermal can still reduce it under
genuine CRITICAL heat (safety vs. hardware survival), but context never does.

## Why this pairs with the fusion

The highest-value piece — **map-predictive sign detection** — is only possible
once the OSM map is on-device (which the fusion adds). The map's upcoming-feature
lookup is the same data structure the fuser already queries, so the surge logic
reuses it. Implementing this right after the fusion is the natural order.

## Expected effect

Most of a drive is open road with no lead vehicle and no nearby junction — so B,
C, and D would all be in their reduced state most of the time, cutting sustained
compute/heat substantially, while A (driver) and map-predicted sign surges keep
the safety-critical capture intact. This attacks the root thermal problem
(sustained full-pipeline load) without the missed-event cost of naive throttling.
EOF
