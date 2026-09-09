package org.flightdesk.radar;

import android.graphics.*;
import android.view.View;

/** Pixelates cached logo artwork without changing its source colors. */
final class AirlineLogoView extends View {
  final MainActivity host;
  final String call, routeIata;
  final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

  AirlineLogoView(MainActivity host, String call, String routeIata) {
    super(host);
    this.host = host;
    this.call = call;
    this.routeIata = routeIata;
    setContentDescription("航空公司标志");
  }

  protected void onDraw(Canvas canvas) {
    // Let the parent card show through transparent areas of the logo.
    Bitmap image = host.logos.image(call, routeIata, this);
    AirlineLogos.Brand brand = host.logos.brand(call);
    if (image == null) {
      paint.setColor(brand == null ? 0xff697b73 : brand.color);
      paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
      paint.setTextAlign(Paint.Align.CENTER);
      String code = host.logos.fallback(call, routeIata);
      paint.setTextSize(Math.min(getHeight() * .18f, host.dp(24)));
      float available = Math.max(1, getWidth() - host.dp(16));
      if (paint.measureText(code) > available)
        paint.setTextSize(paint.getTextSize() * available / paint.measureText(code));
      Paint.FontMetrics metrics = paint.getFontMetrics();
      canvas.drawText(code, getWidth() / 2f,
          getHeight() / 2f - (metrics.ascent + metrics.descent) / 2f, paint);
      return;
    }
    int cols = 48, rows = Math.max(1, Math.round(48f * image.getHeight() / image.getWidth()));
    float cell = Math.min(getWidth() / (float) cols, getHeight() / (float) rows);
    float ox = (getWidth() - cols * cell) / 2, oy = (getHeight() - rows * cell) / 2;
    for (int y = 0; y < rows; y++)
      for (int x = 0; x < cols; x++) {
        int px = image.getPixel(x * image.getWidth() / cols, y * image.getHeight() / rows);
        if (Color.alpha(px) < 40) continue;
        if (Color.red(px) + Color.green(px) + Color.blue(px) < 75) px = 0xffd5ddd9;
        paint.setColor(px);
        canvas.drawCircle(ox + (x + .5f) * cell, oy + (y + .5f) * cell, cell * .36f, paint);
      }
  }
}
