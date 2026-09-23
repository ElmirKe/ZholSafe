package kz.zholsafe.risk;

import kz.zholsafe.model.Contracts;

/** Bounded, named components of an engineering severity SCORE, not collision probability. */
public record RiskComponents(double corridor, double trajectory, double relativeClosing,
                             double ttc, double appearance, double classModifier) {
    public RiskComponents {
        Contracts.range("corridor", corridor, 0d, 1d);
        Contracts.range("trajectory", trajectory, 0d, 1d);
        Contracts.range("relativeClosing", relativeClosing, 0d, 1d);
        Contracts.range("ttc", ttc, 0d, 1d);
        Contracts.range("appearance", appearance, 0d, 1d);
        Contracts.range("classModifier", classModifier, 0d, 1d);
    }
    public double cappedTotal() {
        return Math.min(1d, corridor + trajectory + relativeClosing + ttc + appearance + classModifier);
    }
}
