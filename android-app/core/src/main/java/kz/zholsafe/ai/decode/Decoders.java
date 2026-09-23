package kz.zholsafe.ai.decode;

/** Shared model-output validation helpers. Decoders are the trust boundary for model output. */
final class Decoders {

    private Decoders() { }

    static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    static boolean allFinite(float a, float b, float c, float d) {
        return isFinite(a) && isFinite(b) && isFinite(c) && isFinite(d);
    }

    /**
     * Converts a float-encoded class index to an int only if it is finite, integer-valued and in
     * {@code [0, numClasses)}. Returns -1 otherwise. Never truncates.
     */
    static int strictClassIndex(float v, int numClasses) {
        if (!isFinite(v) || v < 0f || v >= numClasses) {
            return -1;
        }
        int i = (int) v;
        return (float) i == v ? i : -1;
    }
}
