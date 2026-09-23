# tests — cross-module contract artefacts

Module-local unit tests live inside each module (`android-app/core/src/test`,
`zholnet-server/src/test`, `ai-training/tests`). This directory holds artefacts shared across
modules:

- `contracts/hazard-event.v1.schema.json` — JSON Schema for the vehicle → server hazard event.
- `fixtures/hazard-event.v1.example.json` — canonical example used by both Java sides.

`scripts/check_contracts.py` validates the fixture against the schema (no external deps).
