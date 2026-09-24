# Stage 7 reproducible hackathon demo

## One-command non-device self-check

From repository root on Windows run `demo\run_stage7_checks.bat`. `py`, `gradle`, and `mvn` must be
on `PATH`; alternatively set `GRADLE_CMD` and `MAVEN_CMD` to their executable paths. It executes the contract check,
all core and smoke utility tests, the Android debug build, and server tests. It returns non-zero on
the first real failure.

## Vehicle A → Vehicle B sequence

1. If Docker exists, run `docker compose up --build`; otherwise state **SIMULATED SERVER BOUNDARY**.
2. Start the Android app or use DEMO mode. Vehicle A fixture produces a confirmed HORSE track and
   local `RoadRisk WARNING`.
3. `HazardEventBridge` creates the real Stage 6 wire DTO and JSON codec validates the Stage 5 schema.
4. With a real server, POST `/api/v1/hazards`; without it, pass that DTO through the tested nearby
   response boundary used by `RemoteHazardEvaluator`.
5. Vehicle B is roughly 420 m south and heading north. Expected panel:

```text
ZHOLNET
Впереди: ЛОШАДЬ
≈ 420 м
Предупреждение от ZholNet
```

6. Reverse Vehicle B's heading: no `WARNING`. Use an old event: ignored. Register its event ID as
   locally published: ignored.
7. Stop the server: the remote panel becomes unavailable while local RoadGuard continues.
8. Explain DriverGuard and CombinedRisk as separate local inputs; remote metadata never enters them.

This deterministic fallback proves application integration and contracts, not real PostGIS,
Android camera/GPS, road performance or safety certification.
