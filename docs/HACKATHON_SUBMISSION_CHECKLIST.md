# Hackathon submission checklist

- [x] GitHub repository and focused README
- [x] Offline-first architecture and local/remote boundary documented
- [x] Buildable Android MVP and Java 21 ZholNet server
- [x] Deterministic Vehicle A → simulated server boundary → Vehicle B demo
- [x] Automated core, smoke utility, server and contract tests
- [x] Known limitations and failure/resource matrices
- [x] Privacy-minimized metadata contract; no media/biometrics upload
- [x] Team contribution separated from third-party dependencies
- [x] Model provenance caveat retained
- [ ] Real Android camera/GPS device recording
- [ ] Real PostgreSQL/PostGIS end-to-end recording
- [ ] Screenshots/demo video (submission asset placeholder)
- [ ] Final presentation assembled from README, architecture, demo and verification report

Third party: YOLO/Ultralytics model architecture/checkpoint source as documented, ONNX Runtime,
CameraX, OkHttp, Spring Boot, PostgreSQL, PostGIS, Flyway and declared build/test libraries.

Team implementation: typed contracts, pipeline integration, ByteTrack-inspired tracker, image
trajectory, physical estimation/fusion, RoadRiskEngine, DriverGuard temporal analysis,
CombinedRisk, HazardEventBridge, ZholNet application logic, remote advisory and demo integration.
The third-party model architecture is not claimed as original work.
