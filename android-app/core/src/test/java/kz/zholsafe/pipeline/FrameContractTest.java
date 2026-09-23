package kz.zholsafe.pipeline;

import kz.zholsafe.ai.Frame;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrameContractTest {

    @Test
    void rotationMustBeQuadrant() {
        ByteBuffer b = ByteBuffer.allocate(6);
        assertThrows(IllegalArgumentException.class,
                () -> new Frame(2, 1, Frame.PixelFormat.RGB_888, b, 45, 0, Frame.CameraSource.TEST));
        new Frame(2, 1, Frame.PixelFormat.RGB_888, b, 270, 0, Frame.CameraSource.TEST);
    }

    @Test
    void uprightDimensionsSwapFor90And270() {
        Frame f = new Frame(640, 480, Frame.PixelFormat.NV21, ByteBuffer.allocate(640 * 480 * 3 / 2), 90, 1,
                Frame.CameraSource.ROAD);
        assertEquals(480, f.uprightWidth());
        assertEquals(640, f.uprightHeight());
        Frame g = new Frame(640, 480, Frame.PixelFormat.NV21, ByteBuffer.allocate(640 * 480 * 3 / 2), 180, 1,
                Frame.CameraSource.ROAD);
        assertEquals(640, g.uprightWidth());
    }

    @Test
    void packedSizes() {
        assertEquals(640 * 480 * 3 / 2, Frame.packedSize(Frame.PixelFormat.NV21, 640, 480));
        assertEquals(3 * 3 + 2 * 2 * 2, Frame.packedSize(Frame.PixelFormat.NV21, 3, 3)); // odd dims round up
        assertEquals(12, Frame.packedSize(Frame.PixelFormat.RGB_888, 2, 2));
        assertEquals(-1, Frame.packedSize(Frame.PixelFormat.YUV_420_888, 2, 2));
    }
}
