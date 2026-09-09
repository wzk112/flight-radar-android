package org.flightdesk.radar;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Offline silhouette, common metre scale across types and a short scan reveal. */
final class AirframeView extends View {
  final Bitmap bitmap;
  final float span;
  final Paint paint = new Paint(3);
  final long start = android.os.SystemClock.uptimeMillis();

  AirframeView(Context context, Bitmap bitmap, float span) {
    super(context);
    this.bitmap = bitmap;
    this.span = span;
    setContentDescription("机型俯视轮廓，按翼展比例显示");
  }

  protected void onDraw(Canvas c) {
    super.onDraw(c);
    float dp = getResources().getDisplayMetrics().density;
    float size =
        Math.min(
            getWidth() - 32 * dp, Math.min(getHeight() - 16 * dp, Math.max(12, span) * 2.5f * dp));
    float x = (getWidth() - size) / 2, y = (getHeight() - size) / 2;
    float progress = Math.min(1, (android.os.SystemClock.uptimeMillis() - start) / 1400f);
    c.save();
    c.clipRect(x, y, x + size, y + size * progress);
    c.drawBitmap(bitmap, null, new RectF(x, y, x + size, y + size), paint);
    c.restore();
    if (progress < 1) {
      paint.setColor(0xfff9dc86);
      paint.setStrokeWidth(dp);
      c.drawLine(x, y + size * progress, x + size, y + size * progress, paint);
      postInvalidateDelayed(33);
    }
  }
}
