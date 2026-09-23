# Demo scenario: DriverGuard prolonged closure × road hazard ⇒ combined CRITICAL

Deterministic Stage 4.3 demo of the fused risk chain. It is executed by the JVM test
`CombinedRiskDemoSequenceTest` (`android-app/core/src/test/java/kz/zholsafe/risk/`), which drives
the REAL production components — `SyntheticDriverObservationProvider` → `DriverGuardProcessor` →
`TemporalDriverStateAnalyzer` → `DriverRiskEngine` — with genuine `RoadRiskSnapshot` values on
the road side and `CombinedRiskProcessor` + `CombinedRiskEngine` for fusion. Nothing in the
final risk path is fabricated. EXPERIMENTAL DEMO THRESHOLDS (not medical): prolonged closure
WARNING at 1.5 s, CRITICAL at 3.0 s of continuous source-time closure.

Driver script (100 ms source-time frames; eyes close at 2.0 s, otherwise normal):

| time (s) | event | driver risk | road risk | combined |
|---|---|---|---|---|
| T0 = 1.0 | driver normal | NORMAL | NORMAL | NORMAL |
| T1 = 2.0 | eyes close (blink-length so far) | NORMAL | NORMAL | NORMAL |
| T2 = 3.0 | closure continues (1.0 s) | CAUTION (`PROLONGED_EYE_CLOSURE`) | NORMAL | CAUTION |
| T3 = 3.5 | prolonged-closure threshold crossed | WARNING | NORMAL | WARNING |
| T4 = 3.6 | road hazard appears | WARNING | CAUTION | WARNING (NOT CRITICAL) |
| T5 = 3.8 | road risk rises | WARNING | WARNING | CRITICAL + `COMBINED_HAZARD_ESCALATION` |
| T6 = 4.0 | stable | WARNING (closure 2.0 s < 3.0 s) | WARNING | CRITICAL + `COMBINED_HAZARD_ESCALATION` |

Notes: the PERCLOS window is younger than its maturity budget during the demo, so the metric is
honestly `UNAVAILABLE` (not "0"). No collision probability is computed anywhere in the chain.
