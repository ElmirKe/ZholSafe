package kz.zholsafe.camera;

import androidx.annotation.MainThread;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ConcurrentCamera;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.lifecycle.LifecycleOwner;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import kz.zholsafe.logging.ZLog;

/**
 * Binds the road (rear) and driver (front) {@link LiveCamera}s TOGETHER with CameraX concurrent
 * camera mode. CameraX refuses two separate {@code bindToLifecycle} calls for different cameras on
 * one lifecycle owner; streaming both at once needs a single call with both configurations, and
 * only on phones that report a front+back concurrent pair.
 *
 * <p>Each member registers its selector and use cases when its camera provider is ready; the
 * group binds once every expected member has registered. If the phone has no such pair, or
 * binding fails, EVERY member is failed with the reason — its pipeline then reports UNAVAILABLE
 * (grey on screen), never a silent single camera.
 */
public final class ConcurrentCameraGroup {

    private static final String TAG = "ConcurrentCameras";

    private final LifecycleOwner lifecycleOwner;
    private final Map<LiveCamera.Lens, Member> members = new EnumMap<>(LiveCamera.Lens.class);

    private record Member(LiveCamera camera, CameraSelector selector, UseCaseGroup useCases) { }

    public ConcurrentCameraGroup(LifecycleOwner lifecycleOwner) {
        this.lifecycleOwner = lifecycleOwner;
    }

    @MainThread
    void register(LiveCamera camera, LiveCamera.Lens lens, CameraSelector selector, UseCaseGroup useCases,
                  ProcessCameraProvider provider) {
        members.put(lens, new Member(camera, selector, useCases));
        if (members.size() == LiveCamera.Lens.values().length) {
            bind(provider);
        }
    }

    @MainThread
    void unregister(LiveCamera.Lens lens) {
        members.remove(lens);
    }

    private void bind(ProcessCameraProvider provider) {
        if (!supportsFrontAndBack(provider)) {
            failAll("this phone cannot stream the front and rear cameras at the same time", null);
            return;
        }
        List<ConcurrentCamera.SingleCameraConfig> configs = new ArrayList<>();
        for (Member m : members.values()) {
            configs.add(new ConcurrentCamera.SingleCameraConfig(m.selector(), m.useCases(), lifecycleOwner));
        }
        try {
            provider.bindToLifecycle(configs);
            ZLog.i(TAG, "bound road + driver cameras concurrently");
        } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
            failAll("concurrent camera bind failed: " + e.getMessage(), e);
        }
    }

    private static boolean supportsFrontAndBack(ProcessCameraProvider provider) {
        for (List<CameraInfo> combination : provider.getAvailableConcurrentCameraInfos()) {
            boolean front = false;
            boolean back = false;
            for (CameraInfo info : combination) {
                front |= info.getLensFacing() == CameraSelector.LENS_FACING_FRONT;
                back |= info.getLensFacing() == CameraSelector.LENS_FACING_BACK;
            }
            if (front && back) {
                return true;
            }
        }
        return false;
    }

    private void failAll(String reason, Throwable cause) {
        ZLog.w(TAG, reason);
        for (Member m : new ArrayList<>(members.values())) {
            m.camera().failFromGroup(reason, cause);
        }
    }
}
