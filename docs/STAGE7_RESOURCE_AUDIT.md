# Stage 7 resource and backpressure audit

| Resource | Bound/default | Drop/expiry behavior | Failure behavior |
|---|---|---|---|
| Camera frames | `LatestFrameQueue`: one pending frame | Older pending frame recycled when superseded | Processing continues with newest frame; shutdown drains/recycles |
| Tracker objects/history | `TrackingConfig` capacities and bounded per-track history | Deterministic capacity selection; expired tracks removed; IDs never reused | Unavailable input freezes/degrades instead of fabricating empty road |
| Trajectory history | Configured recent sample window | Old samples excluded | Missing/invalid ordering propagates unavailable |
| Physical history | Configured per-track window/capacity | Old/stale track samples rejected/reset | Conflicts stay conflicted; no alarming-value cherry-pick |
| DriverGuard history | Observation cap + time window | Old observations evicted; gaps break continuity | Missing evidence is unknown/unavailable, not open/normal |
| Hazard bridge cooldown state | `suppressionTrackCapacity` | Oldest track entry removed | Missing/stale GPS skips publication only |
| Publisher queue | `RetryQueueConfig` bounded queue/attempts/age | Deterministic oldest drop; stale events discarded | Dead server cannot grow queue or retry forever |
| Self-event registry | 256 IDs, ten minutes | Oldest removed at capacity; timed expiry | Process restart loses memory, affecting only self-filter precision |
| Nearby response | Request limit 50, radius 2,000 m | Server and client bounds apply | Bad/offline response becomes remote unavailable |
| Remote polling | One scheduler, one in-flight request | Overlapping tick skipped; 2 s interval | Stop generation rejects late callbacks; local pipeline unaffected |
| Executors | One pipeline worker, one publisher worker, one foreground poller | Explicit stop/close/shutdown | No thread-per-frame or thread-per-poll creation |

No unbounded accumulation was found in the main MVP path. The values are implementation bounds,
not safety certification thresholds.
