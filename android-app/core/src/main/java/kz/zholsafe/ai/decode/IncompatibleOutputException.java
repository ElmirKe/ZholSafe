package kz.zholsafe.ai.decode;

import java.util.Arrays;

/** Fail-fast: the model's tensors do not match the ModelSpec/decoder contract. */
public class IncompatibleOutputException extends Exception {

    public IncompatibleOutputException(String message) {
        super("MODEL_INCOMPATIBLE: " + message);
    }

    public static IncompatibleOutputException shape(String expected, long[] got) {
        return new IncompatibleOutputException("expected " + expected + ", received output shape " + Arrays.toString(got));
    }
}
