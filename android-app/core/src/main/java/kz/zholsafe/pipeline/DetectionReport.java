package kz.zholsafe.pipeline;

import kz.zholsafe.model.Detection;
import kz.zholsafe.model.ObjectClass;
import kz.zholsafe.physical.PhysicalEstimationSnapshot;
import kz.zholsafe.physical.PhysicalObjectEstimate;
import kz.zholsafe.physical.TtcEstimate;
import kz.zholsafe.tracking.TrackState;
import kz.zholsafe.risk.ObjectRiskAssessment;
import kz.zholsafe.risk.RiskLevel;
import kz.zholsafe.risk.RoadRiskSnapshot;
import kz.zholsafe.trajectory.ObjectTrajectory;

import java.util.Locale;
import java.util.Map;

/**
 * Renders the Stage 2 part of the engineering overlay. Wording rules: objects are DETECTED, never
 * "collision"; an unavailable snapshot is shown as DETECTION UNAVAILABLE, never as zero counts.
 */
public final class DetectionReport {

    private DetectionReport() { }

    public static String render(RoadDetectionProcessor p) {
        DetectionSnapshot s = p.latest();
        StringBuilder b = new StringBuilder(256);
        b.append(p.statusLine()).append('\n');
        if (!s.available()) {
            b.append("DETECTION UNAVAILABLE (").append(s.detectorState()).append(")\n");
            b.append("TRACKING UNAVAILABLE (").append(p.latestTracking().status()).append(")\n");
            b.append("IMAGE TRAJECTORY UNAVAILABLE (").append(p.latestTrajectory().status()).append(")\n");
            b.append("PHYSICAL ESTIMATION UNAVAILABLE (").append(p.latestPhysical().status()).append(")\n");
            b.append("ROAD RISK UNAVAILABLE (").append(p.latestRoadRisk().status()).append(")\n");
            return b.toString();
        }
        b.append(String.format(Locale.ROOT, "INFER FPS ~%.1f  DETECTOR %.1f ms (pre %.1f / inf %.1f / post %.1f)%n",
                p.inferenceFps(), p.recentTotalMillis(),
                s.timings().preprocessNanos() / 1e6, s.timings().inferenceNanos() / 1e6, s.timings().postprocessNanos() / 1e6));
        Map<ObjectClass, Integer> counts = s.countByClass();
        b.append("DETECTIONS:");
        for (ObjectClass c : ObjectClass.values()) {
            if (c == ObjectClass.UNKNOWN) continue;
            b.append(' ').append(c.name()).append(':').append(counts.get(c));
        }
        b.append('\n');
        int shown = 0;
        for (Detection d : s.detections()) {
            if (shown++ >= 3) {
                b.append("  …\n");
                break;
            }
            b.append(String.format(Locale.ROOT, "  %s DETECTED %.2f [%.0f,%.0f,%.0f,%.0f]%n",
                    d.objectClass().name(), d.confidence(), d.box().x1(), d.box().y1(), d.box().x2(), d.box().y2()));
        }
        if (s.detections().isEmpty()) {
            b.append("  NO DETECTIONS\n");
        }
        TrackingSnapshot tracks = p.latestTracking();
        if (!tracks.available() || tracks.frameTimestampNanos() != s.frameTimestampNanos()) {
            b.append("TRACKING UNAVAILABLE (").append(tracks.status()).append(")\n");
        } else {
            b.append("TRACKS: confirmed ").append(tracks.confirmedCount())
                    .append(" tentative ").append(tracks.tentativeCount())
                    .append(" lost ").append(tracks.lostCount()).append('\n');
        }
        TrajectorySnapshot trajectory = p.latestTrajectory();
        if (!trajectory.available() || trajectory.frameTimestampNanos() != s.frameTimestampNanos()) {
            b.append("IMAGE TRAJECTORY UNAVAILABLE (").append(trajectory.status()).append(")\n");
        } else {
            b.append("IMAGE TRAJECTORIES (normalized only): ").append(trajectory.availableCount())
                    .append('/').append(trajectory.objects().size()).append(" available\n");
            int count = 0;
            for (ObjectTrajectory object : trajectory.objects()) {
                if (!object.available()) continue;
                if (count++ >= 3) break;
                b.append("  #").append(object.trackId()).append(' ').append(object.approach())
                        .append(" image-scale / ").append(object.motion().direction()).append(" image-motion\n");
            }
        }
        PhysicalEstimationSnapshot physical = p.latestPhysical();
        if (!physical.available() || physical.frameTimestampNanos() != s.frameTimestampNanos()) {
            b.append("PHYSICAL ESTIMATION UNAVAILABLE (").append(physical.status()).append(")\n");
        } else {
            b.append("PHYSICAL DIAGNOSTICS (EXPERIMENTAL, NOT WARNINGS): ")
                    .append(physical.objects().size()).append(" tracks\n");
            int count = 0;
            for (PhysicalObjectEstimate object : physical.objects()) {
                if (object.trackState() != TrackState.CONFIRMED || count++ >= 3) continue;
                b.append("  #").append(object.trackId()).append(" depth ");
                if (object.distance().available()) {
                    b.append(String.format(Locale.ROOT, "~%.1f m [%s/%s]",
                            object.distance().meters(), object.distance().method(), object.distance().quality()));
                } else b.append("UNAVAILABLE (").append(object.distance().reason()).append(')');
                b.append("  relative rate ");
                if (object.rangeRate().available()) {
                    b.append(String.format(Locale.ROOT, "~%.1f m/s [%s]",
                            object.rangeRate().rangeRateMps(), object.rangeRate().quality()));
                } else b.append("UNAVAILABLE (").append(object.rangeRate().reason()).append(')');
                appendTtc(b, "selected TTC", object.selectedTtc());
                appendTtc(b, "metric TTC", object.metricTtc());
                appendTtc(b, "optical TTC (uncalibrated)", object.imageScaleTtc());
                b.append('\n');
            }
        }
        RoadRiskSnapshot risk = p.latestRoadRisk();
        if (!risk.available() || risk.frameTimestampNanos() != s.frameTimestampNanos()) {
            b.append("ROAD RISK UNAVAILABLE (").append(risk.status()).append(")\n");
        } else {
            b.append("ROAD RISK ").append(risk.highestLevel().orElseThrow())
                    .append(" (EXPERIMENTAL ENGINEERING SEVERITY, NOT PROBABILITY / NO ALERT)\n");
            int shownRisk = 0;
            for (ObjectRiskAssessment object : risk.objects()) {
                if (object.level() == RiskLevel.NORMAL || shownRisk++ >= 3) continue;
                b.append("  ").append(object.objectClass()).append(" #").append(object.trackId())
                        .append(' ').append(object.level()).append(" [").append(object.evidenceQuality())
                        .append("] ").append(object.reasons()).append('\n');
            }
        }
        return b.toString();
    }

    private static void appendTtc(StringBuilder b, String label, TtcEstimate ttc) {
        b.append("  ").append(label).append(' ');
        if (ttc.available()) {
            b.append(String.format(Locale.ROOT, "~%.1f s [%s/%s]", ttc.seconds(),
                    ttc.method(), ttc.quality()));
        } else b.append("UNAVAILABLE (").append(ttc.reason()).append(')');
    }
}
