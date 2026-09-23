# datasets

Raw data is **not** committed. Put datasets under `datasets/<name>/` (git-ignored) with a
YOLO-style `data.yaml`. Candidate public sources for the initial classes (person, dog, horse,
cow, sheep) include COCO-derived subsets; goat and camel require custom collection/annotation.
Document licence and provenance for every source in `datasets/<name>/SOURCES.md`.
