package kz.zholsafe.risk;

import kz.zholsafe.model.Contracts;
import kz.zholsafe.model.ObjectClass;
import kz.zholsafe.physical.EvidenceQuality;

import java.util.List;
import java.util.Objects;

/** Currently observed confirmed track only. Score in [0,1] is engineering severity, NOT probability. */
public record ObjectRiskAssessment(int trackId, ObjectClass objectClass, long timestampNanos,
        RiskLevel level, double engineeringScore, EvidenceQuality evidenceQuality,
        RiskComponents components, List<RiskReason> reasons, List<RiskEvidence> evidence) {
    public ObjectRiskAssessment {
        if (trackId <= 0 || timestampNanos <= 0) throw new IllegalArgumentException("invalid track/source time");
        Objects.requireNonNull(objectClass, "objectClass");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(evidenceQuality, "evidenceQuality");
        Objects.requireNonNull(components, "components");
        Contracts.range("engineeringScore (NOT probability)", engineeringScore, 0d, 1d);
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidenceQuality == EvidenceQuality.UNAVAILABLE || evidence.isEmpty()
                || (level != RiskLevel.NORMAL && reasons.isEmpty())) {
            throw new IllegalArgumentException("current risk requires evidence and non-NORMAL reasons");
        }
        if (Double.compare(engineeringScore, components.cappedTotal()) != 0) {
            throw new IllegalArgumentException("score must equal capped named components");
        }
    }
}
