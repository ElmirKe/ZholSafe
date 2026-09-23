package kz.zholsafe.pipeline;

import kz.zholsafe.ai.Frame;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticFrameSourceTest {

    @Test
    void producesDeterministicTaggedFramesAndStopsAtMax() throws Exception {
        AtomicLong clock = new AtomicLong();
        SyntheticFrameSource src = new SyntheticFrameSource(8, 4, 90, 30, 5, clock::incrementAndGet, n -> { });
        List<Frame> got = new ArrayList<>();
        List<Integer> firstBytes = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(5);
        src.start(new FrameSource.Listener() {
            @Override public void onFrame(Frame f) {
                synchronized (got) {
                    got.add(f);
                    firstBytes.add(f.data().get(5) & 0xFF);
                }
                done.countDown();
            }
            @Override public void onSourceError(String d, Throwable c) { throw new AssertionError(d); }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        Thread.sleep(50);
        assertFalse(src.isRunning(), "source stops itself at maxFrames");
        assertEquals(5, src.producedCount());
        synchronized (got) {
            Frame f = got.get(0);
            assertEquals(8, f.width());
            assertEquals(4, f.height());
            assertEquals(90, f.rotationDegrees());
            assertEquals(Frame.PixelFormat.NV21, f.format());
            assertEquals(Frame.CameraSource.DEMO_SYNTHETIC, f.source());
            assertEquals(Frame.packedSize(Frame.PixelFormat.NV21, 8, 4), f.data().limit());
            // luma(x=5,y=0,frame n) = (5 + 3n) & 0xFF — deterministic
            assertEquals(List.of(5, 8, 11, 14, 17), firstBytes);
            // timestamps strictly increasing
            for (int i = 1; i < got.size(); i++) {
                assertTrue(got.get(i).timestampNanos() > got.get(i - 1).timestampNanos());
            }
        }
    }

    @Test
    void stopIsIdempotentAndTerminatesThread() throws Exception {
        SyntheticFrameSource src = new SyntheticFrameSource(4, 4, 0, 1000);
        CountDownLatch any = new CountDownLatch(1);
        src.start(new FrameSource.Listener() {
            @Override public void onFrame(Frame f) { any.countDown(); }
            @Override public void onSourceError(String d, Throwable c) { }
        });
        assertTrue(any.await(2, TimeUnit.SECONDS));
        src.stop();
        src.stop();
        assertFalse(src.isRunning());
        assertFalse(Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().equals("zs-demo-source") && t.isAlive()));
    }
}
