/**
 * CameraX integration (Stage 1): {@code RoadCamera} and {@code DriverCamera} implement
 * {@link kz.zholsafe.pipeline.FrameSource}, converting ImageProxy → {@link kz.zholsafe.ai.Frame}
 * off the UI thread and handing frames to a {@link kz.zholsafe.pipeline.LatestFrameQueue}.
 */
package kz.zholsafe.camera;
