package kz.zholsafe.location;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic demo/test provider; it performs no Android or network access. */
public final class SyntheticLocationProvider implements LocationProvider {
    private final AtomicReference<LocationFix> fix = new AtomicReference<>();

    public SyntheticLocationProvider() {}
    public SyntheticLocationProvider(LocationFix initial) { fix.set(initial); }
    public void set(LocationFix value) { fix.set(value); }
    public void clear() { fix.set(null); }
    @Override public Optional<LocationFix> latestFix() { return Optional.ofNullable(fix.get()); }
}
