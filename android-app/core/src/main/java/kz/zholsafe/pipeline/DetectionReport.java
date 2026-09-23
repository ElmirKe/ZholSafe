package kz.zholsafe.pipeline;

import kz.zholsafe.model.Detection;
import kz.zholsafe.model.ObjectClass;
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
        return b.toString();
    }
}
