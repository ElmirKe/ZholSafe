package kz.zholsafe.network;

import kz.zholsafe.config.RoadRiskConfig;
import kz.zholsafe.config.TrajectoryConfig;
import kz.zholsafe.location.LocationFix;
import kz.zholsafe.location.LocationQuality;
import kz.zholsafe.model.BoundingBox;
import kz.zholsafe.model.Estimate;
import kz.zholsafe.model.ObjectClass;
import kz.zholsafe.physical.*;
import kz.zholsafe.pipeline.TrackingSnapshot;
import kz.zholsafe.pipeline.TrajectorySnapshot;
import kz.zholsafe.risk.RoadRiskEngine;
import kz.zholsafe.risk.RoadRiskSnapshot;
import kz.zholsafe.tracking.*;
import kz.zholsafe.trajectory.LinearImageTrajectoryEstimator;

import java.time.Instant;
import java.util.List;
import java.util.OptionalDouble;

final class Stage6TestFixtures {
    static final long SECOND = 1_000_000_000L;
    record Pipeline(RoadRiskSnapshot risk, TrackingSnapshot tracking, PhysicalEstimationSnapshot physical) {}
    private Stage6TestFixtures() {}

    static Pipeline warning(long timestamp, ObjectClass type) {
        return pipeline(timestamp, type, true, false);
    }

    static Pipeline normal(long timestamp, ObjectClass type) {
        return pipeline(timestamp, type, false, false);
    }

    static Pipeline critical(long timestamp, ObjectClass type) {
        return pipeline(timestamp, type, false, true);
    }

    private static Pipeline pipeline(long timestamp, ObjectClass type, boolean metricWarning, boolean metricCritical) {
        float center = metricWarning || metricCritical ? 640 : 100;
        TrackObservation a = observation(timestamp - 2 * SECOND, 60, center);
        TrackObservation b = observation(timestamp - SECOND, 60, center);
        TrackObservation c = observation(timestamp, 60, center);
        TrackedObject object = new TrackedObject(42, type, .82f, c.box(),
                List.of(a.center(), b.center(), c.center()), MovementClass.UNKNOWN,
                Estimate.unavailable(), Estimate.unavailable(), true, 3, timestamp);
        TrackView view = new TrackView(object, TrackState.CONFIRMED, 3, 0, List.of(a,b,c));
        TrackingSnapshot tracking = new TrackingSnapshot(timestamp, 1280, 720,
                TrackingSnapshot.Status.READY, List.of(view));
        TrajectorySnapshot trajectory = new LinearImageTrajectoryEstimator(TrajectoryConfig.defaults()).estimate(tracking);
        PhysicalEstimationSnapshot physical;
        if (metricWarning || metricCritical) {
            double meters = metricCritical ? 12 : 16;
            double closing = metricCritical ? -8 : -4;
            double seconds = metricCritical ? 1.5 : 4;
            DistanceEstimate distance = DistanceEstimate.of(meters, DistanceMethod.GROUND_PLANE,
                    EvidenceQuality.MEDIUM, timestamp);
            RangeRateEstimate rate = RangeRateEstimate.of(closing, 0, 3, EvidenceQuality.MEDIUM, timestamp);
            TtcEstimate ttc = TtcEstimate.of(seconds, TtcMethod.METRIC_RANGE, EvidenceQuality.MEDIUM, timestamp);
            PhysicalObjectEstimate estimate = new PhysicalObjectEstimate(42, type, TrackState.CONFIRMED,
                    timestamp, distance, rate, ttc, TtcEstimate.unavailable(timestamp,
                    PhysicalReason.INSUFFICIENT_HISTORY), ttc);
            physical = new PhysicalEstimationSnapshot(timestamp, 1280, 720,
                    PhysicalEstimationSnapshot.Status.READY, TrackingSnapshot.Status.READY,
                    TrajectorySnapshot.Status.READY, List.of(estimate));
        } else {
            physical = PhysicalEstimationProcessor.unavailableByDefault().analyze(tracking, trajectory);
        }
        RoadRiskSnapshot risk = new RoadRiskEngine(RoadRiskConfig.defaults()).evaluate(tracking, trajectory, physical);
        return new Pipeline(risk, tracking, physical);
    }

    static LocationFix location(long elapsed) {
        return new LocationFix(43.238, 76.945, Instant.parse("2026-09-23T12:00:00Z"), elapsed,
                5, OptionalDouble.of(90), OptionalDouble.empty(), LocationQuality.PRECISE);
    }

    static NetworkHazardEvent event(Instant timestamp) {
        return new NetworkHazardEvent("event-1", 1, "anon-test", NetworkHazardType.HORSE,
                NetworkSeverity.WARNING, .82f, .7f, 43.238, 76.945, timestamp, "ACTIVE",
                90f, 15f, 3f);
    }

    private static TrackObservation observation(long timestamp, float size, float center) {
        return new TrackObservation(timestamp, new BoundingBox(center-size/2, 500-size,
                center+size/2, 500), .82f);
    }
}
