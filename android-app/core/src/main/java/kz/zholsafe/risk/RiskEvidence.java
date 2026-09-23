package kz.zholsafe.risk;

import kz.zholsafe.model.Contracts;
import kz.zholsafe.physical.EvidenceQuality;
import kz.zholsafe.physical.PhysicalReason;

import java.util.Objects;
import java.util.Optional;

/** Typed observation: optional finite number with explicit unit; not a calibrated probability. */
public record RiskEvidence(RiskEvidenceType type, EvidenceSource source, EvidenceQuality quality,
                           boolean numericAvailable, double value, EvidenceUnit unit,
                           Optional<PhysicalReason> physicalReason) {
    public RiskEvidence {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(physicalReason, "physicalReason");
        if (source == EvidenceSource.PHYSICAL_STATUS) {
            if (physicalReason.isEmpty() || physicalReason.get() == PhysicalReason.AVAILABLE) {
                throw new IllegalArgumentException("physical status needs an explicit failure reason");
            }
        } else if (physicalReason.isPresent()) {
            throw new IllegalArgumentException("nonphysical evidence cannot claim physical failure");
        }
        boolean expectsNumber = switch (type) {
            case METRIC_DISTANCE_AVAILABLE, METRIC_CLOSING, METRIC_TTC_SHORT,
                 IMAGE_SCALE_TTC_SHORT, PREDICTED_CORRIDOR_ENTRY,
                 MOVING_TOWARD_CORRIDOR, APPARENT_APPROACH, LARGE_IN_FRAME -> true;
            default -> false;
        };
        if (numericAvailable != expectsNumber) {
            throw new IllegalArgumentException("numeric presence must match evidence type");
        }
        EvidenceSource expectedSource = switch (type) {
            case METRIC_DISTANCE_AVAILABLE, METRIC_CLOSING -> EvidenceSource.METRIC_RANGE;
            case METRIC_TTC_SHORT -> EvidenceSource.METRIC_TTC;
            case IMAGE_SCALE_TTC_SHORT -> EvidenceSource.OPTICAL_EXPANSION;
            case PREDICTED_CORRIDOR_ENTRY, MOVING_TOWARD_CORRIDOR, APPARENT_APPROACH,
                 APPARENT_RECEDE, TRAJECTORY_UNAVAILABLE -> EvidenceSource.IMAGE_TRAJECTORY;
            case LARGE_HAZARD_CLASS -> EvidenceSource.CLASS_PRIOR;
            case PHYSICAL_ESTIMATE_UNAVAILABLE, PHYSICAL_LOW_QUALITY,
                 TTC_CONFLICT -> EvidenceSource.PHYSICAL_STATUS;
            default -> EvidenceSource.TRACKING;
        };
        if (source != expectedSource) throw new IllegalArgumentException("evidence source/type mismatch");
        if (numericAvailable) {
            Contracts.finite("evidence value", value);
            EvidenceUnit expected = switch (type) {
                case METRIC_DISTANCE_AVAILABLE -> EvidenceUnit.METERS_OPTICAL_DEPTH;
                case METRIC_CLOSING -> EvidenceUnit.METERS_PER_SECOND_RELATIVE;
                case METRIC_TTC_SHORT, IMAGE_SCALE_TTC_SHORT, PREDICTED_CORRIDOR_ENTRY -> EvidenceUnit.SECONDS;
                case MOVING_TOWARD_CORRIDOR -> EvidenceUnit.NORMALIZED_PER_SECOND;
                case APPARENT_APPROACH -> EvidenceUnit.LOG_AREA_PER_SECOND;
                case LARGE_IN_FRAME -> EvidenceUnit.NORMALIZED_FRACTION;
                default -> EvidenceUnit.NONE;
            };
            if (unit != expected || quality == EvidenceQuality.UNAVAILABLE) {
                throw new IllegalArgumentException("numeric evidence requires the type's unit and quality");
            }
            if (switch (type) {
                case METRIC_DISTANCE_AVAILABLE, METRIC_TTC_SHORT, IMAGE_SCALE_TTC_SHORT,
                     PREDICTED_CORRIDOR_ENTRY, APPARENT_APPROACH -> value <= 0d;
                case METRIC_CLOSING, LARGE_IN_FRAME -> value < 0d;
                default -> false; // signed normalized horizontal velocity may be negative
            }) throw new IllegalArgumentException("evidence value sign contradicts quantity");
            if (type == RiskEvidenceType.LARGE_IN_FRAME && value > 1d) {
                throw new IllegalArgumentException("normalized area must be <= 1");
            }
        } else if (!Double.isNaN(value) || unit != EvidenceUnit.NONE) {
            throw new IllegalArgumentException("nonnumeric evidence must carry NaN/NONE");
        }
    }

    /** Convenience constructor for evidence with no physical failure detail. */
    public RiskEvidence(RiskEvidenceType type, EvidenceSource source, EvidenceQuality quality,
                        boolean numericAvailable, double value, EvidenceUnit unit) {
        this(type, source, quality, numericAvailable, value, unit, Optional.empty());
    }

    public static RiskEvidence physicalFlag(RiskEvidenceType type, PhysicalReason reason,
                                             EvidenceQuality quality) {
        return new RiskEvidence(type, EvidenceSource.PHYSICAL_STATUS, quality,
                false, Double.NaN, EvidenceUnit.NONE, Optional.of(reason));
    }

    public static RiskEvidence flag(RiskEvidenceType type, EvidenceSource source, EvidenceQuality quality) {
        return new RiskEvidence(type, source, quality, false, Double.NaN, EvidenceUnit.NONE);
    }

    public static RiskEvidence value(RiskEvidenceType type, EvidenceSource source,
                                     EvidenceQuality quality, double v, EvidenceUnit unit) {
        return new RiskEvidence(type, source, quality, true, v, unit);
    }
}
