package kz.zholsafe.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.camera.view.PreviewView;

import kz.zholsafe.pipeline.TrackingSnapshot;
import kz.zholsafe.tracking.TrackState;
import kz.zholsafe.tracking.TrackView;
import kz.zholsafe.tracking.TrackedObject;

import java.util.Locale;

/**
 * Lightweight bounding-box overlay drawn above the {@link PreviewView}.
 *
 * <h2>Coordinate mapping</h2>
 * Tracks are in UPRIGHT source pixels ({@code snapshot.uprightWidth × uprightHeight}). The
 * PreviewView is configured with {@code scaleType=fitCenter}, so the upright image is drawn with
 * uniform scale {@code s = min(viewW/uw, viewH/uh)} and centred offsets — exactly a letterbox,
 * which this view reproduces. If the scale type is changed to a centre-crop mode this mapping is
 * WRONG (the preview would be cropped); {@link #setSupportedScaleType} refuses to draw in that
 * case so no misleading boxes appear. This mapping assumes the preview surface's aspect equals the
 * analysis frame's aspect; when they differ (some devices pick different sensor modes for preview
 * vs analysis) boxes may be offset — the overlay is an engineering aid and is reported as
 * NOT VERIFIED on hardware.
 */
public final class DetectionOverlayView extends View {

    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    @Nullable private TrackingSnapshot snapshot;
    private boolean mappingSupported = true;

    public DetectionOverlayView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        box.setStyle(Paint.Style.STROKE);
        box.setStrokeWidth(4f);
        box.setColor(Color.YELLOW);
        text.setColor(Color.YELLOW);
        text.setTextSize(36f);
        setWillNotDraw(false);
    }

    public void setSupportedScaleType(PreviewView.ScaleType type) {
        mappingSupported = type == PreviewView.ScaleType.FIT_CENTER;
        invalidate();
    }

    public void setSnapshot(@Nullable TrackingSnapshot s) {
        snapshot = s;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        TrackingSnapshot s = snapshot;
        if (s == null || !s.available() || !mappingSupported || s.uprightWidth() <= 0 || s.uprightHeight() <= 0) {
            return;
        }
        float vw = getWidth();
        float vh = getHeight();
        float scale = Math.min(vw / s.uprightWidth(), vh / s.uprightHeight());
        float offX = (vw - s.uprightWidth() * scale) / 2f;
        float offY = (vh - s.uprightHeight() * scale) / 2f;
        for (TrackView track : s.tracks()) {
            if (track.state() == TrackState.LOST) continue; // last box is stale, not a live sighting
            TrackedObject d = track.object();
            tmp.set(offX + d.box().x1() * scale, offY + d.box().y1() * scale,
                    offX + d.box().x2() * scale, offY + d.box().y2() * scale);
            canvas.drawRect(tmp, box);
            canvas.drawText(String.format(Locale.ROOT, "%s #%d %.2f%s", d.objectClass().name(), d.trackId(), d.confidence(),
                            track.state() == TrackState.TENTATIVE ? " (tentative)" : ""),
                    tmp.left + 6f, Math.max(36f, tmp.top - 8f), text);
        }
    }
}
