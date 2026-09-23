package kz.zholsafe.pipeline;

import kz.zholsafe.ai.Frame;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class LatestFrameQueueTest {

    private static Frame frame(long ts) {
        return new Frame(2, 2, Frame.PixelFormat.RGB_888, ByteBuffer.allocate(12), 0, ts, Frame.CameraSource.TEST);
    }

    @Test
    void keepsOnlyTheMostRecentFrameAndCountsDrops() {
        LatestFrameQueue q = new LatestFrameQueue();
        Frame f1 = frame(1);
        Frame f2 = frame(2);
        Frame f3 = frame(3);
        q.offer(f1);
        q.offer(f2);
        q.offer(f3);
        assertSame(f3, q.poll());
        assertNull(q.poll());
        assertEquals(2, q.droppedCount());
    }
}
