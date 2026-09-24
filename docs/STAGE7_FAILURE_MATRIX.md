# Stage 7 failure matrix

| Failure/state | Local detection/risk | Network publish | Remote advisory | User-visible behavior |
|---|---|---|---|---|
| Camera unavailable/permission denied | Unavailable; never fabricated normal | No event | Independent if GPS/network work | Camera/model status shown |
| Model unavailable | Explicit MODEL NOT AVAILABLE; no detections | No event | Independent | Retry/demo remains available |
| Invalid ONNX output/detector exception | Detector/pipeline unavailable for affected input | No fabricated event | Independent | Explicit degraded telemetry |
| Tracking/trajectory unavailable | RoadRisk unavailable | No event | Independent | Local diagnostic reason shown |
| Calibration unavailable | Metric distance/TTC unavailable; optical evidence remains qualified | Only eligible local risk may publish | Independent | No false metric precision |
| GPS unavailable/stale | Local camera and risk unchanged | Skipped | LOCATION_UNAVAILABLE/STALE | Local safety continues message |
| Internet/server unavailable/HTTP timeout | Local pipeline unchanged | Bounded retry then failure | NETWORK_UNAVAILABLE | Network degraded, not local failure |
| Invalid server JSON | Local pipeline unchanged | Unchanged | NETWORK_UNAVAILABLE | Explicit remote degradation |
| PostGIS unavailable | Local pipeline unchanged | Server operation fails | Remote unavailable | Local safety continues |
| Remote event stale/expired | Unchanged | N/A | Ignored | No stale advisory |
| Remote event behind | Unchanged | N/A | Suppressed | No warning |
| Own remote event | Unchanged | N/A | Suppressed while ID is remembered | No self-warning |
| Driver observation missing | Road path unchanged; DriverGuard unavailable | Road publication unaffected | Independent | CombinedRisk uses documented degraded mode |

Remote metadata never becomes local detector evidence, RoadRisk, DriverRisk, or CombinedRisk input.
