/**
 * CameraX integration (LIVE mode only). Contains the only code in the app that touches
 * {@code androidx.camera.*}; everything downstream works on {@link kz.zholsafe.ai.Frame}.
 *
 * <p>Road (rear) camera only in Stage 1. The driver (front) camera is a later stage and will
 * be a second {@link kz.zholsafe.pipeline.FrameSource} implementation, not a change to this one.
 */
package kz.zholsafe.camera;
