package kz.zholsafe.location;

import java.util.Optional;

/** Non-blocking location boundary. Missing permission/fix is represented by Optional.empty(). */
public interface LocationProvider {
    Optional<LocationFix> latestFix();
}
