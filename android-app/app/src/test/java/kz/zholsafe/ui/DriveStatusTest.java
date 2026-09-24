package kz.zholsafe.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import kz.zholsafe.ai.Frame;
import kz.zholsafe.config.CombinedRiskConfig;
import kz.zholsafe.config.DriverGuardConfig;
import kz.zholsafe.driver.DriverObservation;
import kz.zholsafe.driver.DriverObservationProvider;
import kz.zholsafe.driver.HeadPose;
import kz.zholsafe.model.ObjectClass;
import kz.zholsafe.physical.EvidenceQuality;
import kz.zholsafe.physical.PhysicalEstimationSnapshot;
import kz.zholsafe.pipeline.DriverGuardProcessor;
import kz.zholsafe.pipeline.PipelineState;
import kz.zholsafe.pipeline.TrackingSnapshot;
import kz.zholsafe.pipeline.TrajectorySnapshot;
import kz.zholsafe.risk.CombinedRiskProcessor;
import kz.zholsafe.risk.EvidenceSource;
import kz.zholsafe.risk.ObjectRiskAssessment;
import kz.zholsafe.risk.RiskComponents;
import kz.zholsafe.risk.RiskEvidence;
import kz.zholsafe.risk.RiskEvidenceType;
import kz.zholsafe.risk.RiskLevel;
import kz.zholsafe.risk.RiskReason;
import kz.zholsafe.risk.RoadRiskSnapshot;

/**
 * End-to-end on the JVM: observation sequences shaped like MediaPipe output go through the REAL
 * {@link DriverGuardProcessor} and {@link CombinedRiskProcessor}; the test checks what the driver
 * is shown and told. Thresholds are the core's EXPERIMENTAL defaults (closure WARNING at 1.5 s,
 * head-away persistence 2 s, face-loss 5 s).
 */
public class DriveStatusTest {

    private static final long FRAME_NS = 66_000_000L; // ~15 fps, like the front camera
    private static final long SECOND = 1_000_000_000L;

    private DriverGuardProcessor guard;
    private CombinedRiskProcessor fusion;
    private long t;

    @Before
    public void setUp() {
        guard = new DriverGuardProcessor(new UnusedProvider(), DriverGuardConfig.defaults());
        fusion = new CombinedRiskProcessor(CombinedRiskConfig.defaults());
        t = 5 * SECOND;
    }

    // ---- observation shapes (as MediaPipeDriverObservationProvider builds them) ----

    private DriverObservation face(float eyeOpenness, float mouth, float pitchDeg) {
        return new DriverObservation(t, true, true, eyeOpenness, eyeOpenness, true, mouth,
                HeadPose.of(0f, pitchDeg, 0f), 0.6f);
    }

    private DriverObservation open() {
        return face(0.9f, 0.05f, 0f);
    }

    private DriverObservation closed() {
        return face(0.05f, 0.05f, 0f);
    }

    /** Face found but eyes not measurable (dark sunglasses): eye values unavailable. */
    private DriverObservation eyesHidden() {
        return new DriverObservation(t, true, false, Float.NaN, Float.NaN, true, 0.05f,
                HeadPose.of(0f, 0f, 0f), 0.6f);
    }

    private interface Obs {
        DriverObservation next();
    }

    private void feed(double seconds, Obs obs) {
        long end = t + (long) (seconds * SECOND);
        while (t < end) {
            t += FRAME_NS;
            guard.processObservation(obs.next());
        }
    }

    private DriveStatus driverStatus() {
        fusion.updateDriver(guard.latestRisk());
        return DriveStatus.from(fusion.evaluate(), true, false, PipelineState.RUNNING, PipelineState.NOT_STARTED);
    }

    // ---- driver ----

    @Test
    public void openEyesShowNoDangerForTheDriverOnly() {
        feed(5, this::open);
        DriveStatus s = driverStatus();
        assertEquals(DriveStatus.Tone.OK, s.tone());
        assertEquals(DriveStatus.Text.OK_DETAIL_DRIVER, s.detail());
        assertNull(s.speech());
    }

    @Test
    public void eyesClosedTwoSecondsSoundsTheSleepAlarm() {
        feed(3, this::open);
        feed(2, this::closed);
        DriveStatus s = driverStatus();
        assertEquals(DriveStatus.Tone.ALARM, s.tone());
        assertEquals(DriveStatus.Kind.SLEEP, s.kind());
        assertEquals(DriveStatus.Text.SLEEP_SPEECH, s.speech());
    }

    @Test
    public void shortClosureIsOnlyACaution() {
        feed(3, this::open);
        feed(1, this::closed);
        DriveStatus s = driverStatus();
        assertEquals(DriveStatus.Tone.CAUTION, s.tone());
        assertEquals(DriveStatus.Kind.FATIGUE, s.kind());
    }

    @Test
    public void blinkIsNotAnAlert() {
        feed(3, this::open);
        feed(0.2, this::closed);
        feed(0.5, this::open);
        assertEquals(DriveStatus.Tone.OK, driverStatus().tone());
    }

    @Test
    public void headDownThreeSecondsSoundsTheAlarm() {
        feed(3, this::open);
        feed(3, () -> face(0.9f, 0.05f, 35f));
        DriveStatus s = driverStatus();
        assertEquals(DriveStatus.Tone.ALARM, s.tone());
        assertEquals(DriveStatus.Kind.ATTENTION, s.kind());
    }

    /** Audit finding on the browser prototype: raising the head must not look like a nod. */
    @Test
    public void headUpIsNotTreatedAsHeadDown() {
        feed(3, this::open);
        feed(3, () -> face(0.9f, 0.05f, -35f));
        assertNotEquals(DriveStatus.Tone.ALARM, driverStatus().tone());
    }

    @Test
    public void persistentYawnIsAFatigueCaution() {
        feed(3, this::open);
        feed(3, () -> face(0.9f, 0.85f, 0f));
        DriveStatus s = driverStatus();
        assertEquals(DriveStatus.Tone.CAUTION, s.tone());
        assertEquals(DriveStatus.Kind.FATIGUE, s.kind());
        assertEquals(DriveStatus.Text.FATIGUE_SPEECH, s.speech());
    }

    /** Audit finding: a lost face must never look like the green "no danger" state. */
    @Test
    public void briefFaceLossIsGreyAndSilent() {
        feed(3, this::open);
        feed(0.5, () -> DriverObservation.noFace(t));
        DriveStatus s = driverStatus();
        assertEquals(DriveStatus.Tone.UNKNOWN, s.tone());
        assertEquals(DriveStatus.Text.FACE_TITLE, s.title());
        assertNull(s.speech());
    }

    @Test
    public void persistentFaceLossIsAnnounced() {
        feed(3, this::open);
        feed(6, () -> DriverObservation.noFace(t));
        DriveStatus s = driverStatus();
        assertEquals(DriveStatus.Tone.UNKNOWN, s.tone());
        assertEquals(DriveStatus.Text.FACE_SPEECH, s.speech());
    }

    @Test
    public void sunglassesAreReportedAsEyesHiddenNotAsOk() {
        feed(3, this::open);
        feed(6, this::eyesHidden);
        DriveStatus s = driverStatus();
        assertEquals(DriveStatus.Tone.UNKNOWN, s.tone());
        assertEquals(DriveStatus.Text.EYES_HIDDEN_TITLE, s.title());
    }

    @Test
    public void unavailableDriverCameraIsNeverGreen() {
        fusion.updateDriver(guard.latestRisk());
        DriveStatus s = DriveStatus.from(fusion.evaluate(), true, false,
                PipelineState.UNAVAILABLE, PipelineState.NOT_STARTED);
        assertEquals(DriveStatus.Tone.UNKNOWN, s.tone());
        assertEquals(DriveStatus.Text.DRIVER_CAMERA_TITLE, s.title());
    }

    @Test
    public void calibrationIsGreyNotGreen() {
        feed(1, this::open);
        fusion.updateDriver(guard.latestRisk());
        DriveStatus s = DriveStatus.from(fusion.evaluate(), true, false,
                PipelineState.RUNNING, PipelineState.NOT_STARTED, true);
        assertEquals(DriveStatus.Tone.UNKNOWN, s.tone());
        assertEquals(DriveStatus.Text.CALIBRATING_TITLE, s.title());
    }

    @Test
    public void alarmStillWinsDuringCalibration() {
        feed(3, this::open);
        feed(2, this::closed);
        fusion.updateDriver(guard.latestRisk());
        DriveStatus s = DriveStatus.from(fusion.evaluate(), true, false,
                PipelineState.RUNNING, PipelineState.NOT_STARTED, true);
        assertEquals(DriveStatus.Tone.ALARM, s.tone());
    }

    // ---- road ----

    @Test
    public void roadWarningSoundsTheRoadAlarm() {
        fusion.updateRoad(road(t, RiskLevel.WARNING));
        DriveStatus s = DriveStatus.from(fusion.evaluate(), false, true,
                PipelineState.NOT_STARTED, PipelineState.RUNNING);
        assertEquals(DriveStatus.Tone.ALARM, s.tone());
        assertEquals(DriveStatus.Kind.ROAD, s.kind());
        assertEquals(DriveStatus.Text.ROAD_SPEECH, s.speech());
    }

    @Test
    public void emptyRoadSaysNoDangerDetectedNotSafe() {
        fusion.updateRoad(road(t, RiskLevel.NORMAL));
        DriveStatus s = DriveStatus.from(fusion.evaluate(), false, true,
                PipelineState.NOT_STARTED, PipelineState.RUNNING);
        assertEquals(DriveStatus.Tone.OK, s.tone());
        assertEquals(DriveStatus.Text.OK_TITLE, s.title());
        assertEquals(DriveStatus.Text.OK_DETAIL_ROAD, s.detail());
    }

    @Test
    public void everyTextHasAResourceName() {
        for (DriveStatus.Text text : DriveStatus.Text.values()) {
            assertNotNull(text.resourceName());
            assertEquals(text.resourceName(), text.resourceName().toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** READY road snapshot at the given level (same shape as core's CombinedRiskTestSupport). */
    private static RoadRiskSnapshot road(long ts, RiskLevel level) {
        if (level == RiskLevel.NORMAL) {
            return new RoadRiskSnapshot(ts, 1280, 720, RoadRiskSnapshot.Status.READY,
                    TrackingSnapshot.Status.READY, TrajectorySnapshot.Status.READY,
                    PhysicalEstimationSnapshot.Status.READY, List.of(),
                    Optional.of(RiskLevel.NORMAL), OptionalInt.empty());
        }
        RiskComponents components = new RiskComponents(0.60d, 0d, 0d, 0d, 0d, 0d);
        ObjectRiskAssessment assessment = new ObjectRiskAssessment(1, ObjectClass.HORSE, ts, level,
                components.cappedTotal(), EvidenceQuality.MEDIUM, components,
                List.of(RiskReason.OBJECT_IN_DRIVING_CORRIDOR),
                List.of(RiskEvidence.flag(RiskEvidenceType.OBJECT_IN_CORRIDOR, EvidenceSource.TRACKING,
                        EvidenceQuality.MEDIUM)));
        return new RoadRiskSnapshot(ts, 1280, 720, RoadRiskSnapshot.Status.READY,
                TrackingSnapshot.Status.READY, TrajectorySnapshot.Status.READY,
                PhysicalEstimationSnapshot.Status.READY, List.of(assessment),
                Optional.of(level), OptionalInt.of(1));
    }

    /** The tests feed observations directly; no frame ever reaches the provider. */
    private static final class UnusedProvider implements DriverObservationProvider {
        @Override
        public String sourceId() {
            return "test";
        }

        @Override
        public DriverObservation provide(Frame frame) {
            throw new AssertionError("frames are not used in this test");
        }
    }
}
