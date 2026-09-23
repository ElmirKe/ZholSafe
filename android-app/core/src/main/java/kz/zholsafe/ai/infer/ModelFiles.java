package kz.zholsafe.ai.infer;

import kz.zholsafe.ai.ModelNotAvailableException;

import java.io.IOException;
import java.io.InputStream;

/**
 * Abstracts "where model files come from" (Android assets, app files dir, plain filesystem for
 * JVM benchmarks). Keeps Context out of core.
 */
public interface ModelFiles {

    boolean exists(String relativePath);

    InputStream open(String relativePath) throws IOException;

    /** Absolute filesystem path usable by the runtime (assets must be materialised first). */
    String absolutePath(String relativePath) throws ModelNotAvailableException;
}
