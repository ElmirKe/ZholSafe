package kz.zholsafe.ui;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import kz.zholsafe.pipeline.PipelineState;
import kz.zholsafe.risk.CombinedRiskReason;
import kz.zholsafe.risk.CombinedRiskSnapshot;
import kz.zholsafe.risk.DriverRiskReason;
import kz.zholsafe.risk.RiskLevel;

/**
 * What the driver sees and hears for one {@link CombinedRiskSnapshot}: a colour tone, the alert
 * kind and three {@link Text} keys (headline, detail, phrase to speak). Pure Java (no Android) so
 * the mapping is unit-tested on the JVM; the Activity resolves each key to the {@code ds_*} string
 * resource of the selected app language (Russian, Kazakh or English).
 *
 * <p>Fail-safe rule: missing evidence is NEVER shown as the green "no danger" state. A lost face,
 * a failed camera or a missing model produce {@link Tone#UNKNOWN} with an explicit reason; the
 * green state only says that no danger was DETECTED, never that the road or driver is safe.
 */
record DriveStatus(Tone tone, Kind kind, Text title, Text detail, Text speech) {

    /** Screen colour / alarm intensity. */
    enum Tone {
        /** Monitoring works and nothing was detected. */
        OK,
        /** Monitoring is (partly) blind: camera, model or face missing. Not an all-clear. */
        UNKNOWN,
        /** Early sign: yawning, looking away, short eye closure. Beep + voice once. */
        CAUTION,
        /** Danger now: microsleep or a road hazard. Siren, vibration, red screen. */
        ALARM
    }

    /** What the alert is about — a change of kind re-announces the alert. */
    enum Kind { NONE, SLEEP, FATIGUE, ATTENTION, ROAD, DRIVER_BLIND, SYSTEM }

    /** Message keys; each maps to string resource {@code ds_<lowercase name>} in every language. */
    enum Text {
        SLEEP_TITLE, SLEEP_DETAIL_LONG, SLEEP_DETAIL_FREQUENT, SLEEP_SPEECH,
        ROAD_TITLE, ROAD_DETAIL, ROAD_DETAIL_DRIVER, ROAD_SPEECH,
        HEAD_DOWN_TITLE, HEAD_DOWN_DETAIL, HEAD_DOWN_SPEECH,
        HIGH_RISK_TITLE, HIGH_RISK_DETAIL, HIGH_RISK_SPEECH,
        DRIVER_CAMERA_TITLE, DRIVER_CAMERA_DETAIL,
        ROAD_CAMERA_TITLE, ROAD_CAMERA_DETAIL,
        FACE_TITLE, FACE_DETAIL, FACE_SPEECH,
        EYES_HIDDEN_TITLE, EYES_HIDDEN_DETAIL,
        FATIGUE_TITLE, FATIGUE_DETAIL, FATIGUE_SPEECH,
        ATTENTION_TITLE, ATTENTION_DETAIL, ATTENTION_SPEECH,
        EYES_CLOSING_TITLE, EYES_CLOSING_DETAIL,
        ROAD_CAUTION_TITLE, ROAD_CAUTION_DETAIL,
        STARTING_TITLE, STARTING_DETAIL,
        OK_TITLE, OK_DETAIL_BOTH, OK_DETAIL_DRIVER, OK_DETAIL_ROAD;

        /** Name of the Android string resource holding this text. */
        String resourceName() {
            return "ds_" + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    DriveStatus {
        Objects.requireNonNull(tone, "tone");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(detail, "detail");
    }

    boolean alarm() {
        return tone == Tone.ALARM;
    }

    Optional<Text> speechText() {
        return Optional.ofNullable(speech);
    }

    /**
     * @param driverExpected the session is supposed to watch the driver (front camera selected)
     * @param roadExpected   the session is supposed to watch the road (rear camera or DEMO)
     */
    static DriveStatus from(CombinedRiskSnapshot snap, boolean driverExpected, boolean roadExpected,
                            PipelineState driverPipeline, PipelineState roadPipeline) {
        List<DriverRiskReason> driver = snap.driverRisk().reasons();
        RiskLevel driverLevel = snap.driverLevel().orElse(null);
        RiskLevel roadLevel = snap.roadLevel().orElse(null);
        RiskLevel combinedLevel = snap.combinedLevel().orElse(null);
        boolean prolonged = driver.contains(DriverRiskReason.PROLONGED_EYE_CLOSURE);

        // 1. Danger first — a real alarm always wins over "partly blind" notices.
        if (atLeast(driverLevel, RiskLevel.WARNING) && (prolonged || driver.contains(DriverRiskReason.HIGH_PERCLOS))) {
            return new DriveStatus(Tone.ALARM, Kind.SLEEP, Text.SLEEP_TITLE,
                    prolonged ? Text.SLEEP_DETAIL_LONG : Text.SLEEP_DETAIL_FREQUENT, Text.SLEEP_SPEECH);
        }
        if (atLeast(roadLevel, RiskLevel.WARNING)) {
            boolean impaired = snap.reasons().contains(CombinedRiskReason.DRIVER_IMPAIRMENT_WITH_ROAD_HAZARD);
            return new DriveStatus(Tone.ALARM, Kind.ROAD, Text.ROAD_TITLE,
                    impaired ? Text.ROAD_DETAIL_DRIVER : Text.ROAD_DETAIL, Text.ROAD_SPEECH);
        }
        if (atLeast(driverLevel, RiskLevel.WARNING) && driver.contains(DriverRiskReason.LOOKING_DOWN)) {
            return new DriveStatus(Tone.ALARM, Kind.ATTENTION, Text.HEAD_DOWN_TITLE, Text.HEAD_DOWN_DETAIL,
                    Text.HEAD_DOWN_SPEECH);
        }
        if (atLeast(combinedLevel, RiskLevel.WARNING)) {
            return new DriveStatus(Tone.ALARM, Kind.SYSTEM, Text.HIGH_RISK_TITLE, Text.HIGH_RISK_DETAIL,
                    Text.HIGH_RISK_SPEECH);
        }

        // 2. Systems that should run but are not delivering evidence.
        if (driverExpected && driverPipeline == PipelineState.UNAVAILABLE) {
            return blind(Kind.SYSTEM, Text.DRIVER_CAMERA_TITLE, Text.DRIVER_CAMERA_DETAIL);
        }
        if (roadExpected && roadPipeline == PipelineState.UNAVAILABLE) {
            return blind(Kind.SYSTEM, Text.ROAD_CAMERA_TITLE, Text.ROAD_CAMERA_DETAIL);
        }
        if (driverExpected && (driver.contains(DriverRiskReason.DRIVER_VISIBILITY_LOST)
                || driver.contains(DriverRiskReason.FACE_NOT_DETECTED))) {
            // Speak only once the loss persisted (VISIBILITY_LOST), not on a single missed frame.
            return new DriveStatus(Tone.UNKNOWN, Kind.DRIVER_BLIND, Text.FACE_TITLE, Text.FACE_DETAIL,
                    driver.contains(DriverRiskReason.DRIVER_VISIBILITY_LOST) ? Text.FACE_SPEECH : null);
        }
        if (driverExpected && driver.contains(DriverRiskReason.INSUFFICIENT_EYE_VISIBILITY)) {
            return blind(Kind.DRIVER_BLIND, Text.EYES_HIDDEN_TITLE, Text.EYES_HIDDEN_DETAIL);
        }

        // 3. Early warnings.
        if (driver.contains(DriverRiskReason.YAWN_LIKE_EVENT) || driver.contains(DriverRiskReason.HIGH_PERCLOS)) {
            return new DriveStatus(Tone.CAUTION, Kind.FATIGUE, Text.FATIGUE_TITLE, Text.FATIGUE_DETAIL,
                    Text.FATIGUE_SPEECH);
        }
        if (driver.contains(DriverRiskReason.HEAD_AWAY) || driver.contains(DriverRiskReason.LOOKING_DOWN)) {
            return new DriveStatus(Tone.CAUTION, Kind.ATTENTION, Text.ATTENTION_TITLE, Text.ATTENTION_DETAIL,
                    Text.ATTENTION_SPEECH);
        }
        if (prolonged || driver.contains(DriverRiskReason.EYES_CLOSED)) {
            return new DriveStatus(Tone.CAUTION, Kind.FATIGUE, Text.EYES_CLOSING_TITLE, Text.EYES_CLOSING_DETAIL, null);
        }
        if (roadLevel == RiskLevel.CAUTION) {
            return new DriveStatus(Tone.CAUTION, Kind.ROAD, Text.ROAD_CAUTION_TITLE, Text.ROAD_CAUTION_DETAIL, null);
        }

        // 4. Nothing detected — but only claim what is actually being watched.
        if (snap.status() == CombinedRiskSnapshot.Status.UNAVAILABLE) {
            return blind(Kind.SYSTEM, Text.STARTING_TITLE, Text.STARTING_DETAIL);
        }
        Text watched = driverLevel != null && roadLevel != null ? Text.OK_DETAIL_BOTH
                : driverLevel != null ? Text.OK_DETAIL_DRIVER : Text.OK_DETAIL_ROAD;
        return new DriveStatus(Tone.OK, Kind.NONE, Text.OK_TITLE, watched, null);
    }

    private static boolean atLeast(RiskLevel level, RiskLevel min) {
        return level != null && level.isAtLeast(min);
    }

    private static DriveStatus blind(Kind kind, Text title, Text detail) {
        return new DriveStatus(Tone.UNKNOWN, kind, title, detail, null);
    }
}
