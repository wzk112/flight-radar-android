package org.flightdesk.radar;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.text.TextUtils;
import android.widget.TextView;

/** Renders Android's full Unicode text coverage through a cached LED-style dot mask. */
final class DotMatrixTextView extends TextView {
  Bitmap dots;
  final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  boolean dirty = true;

  DotMatrixTextView(Context c) {
    super(c);
    init();
  }

  DotMatrixTextView(Context c, AttributeSet a) {
    super(c, a);
    init();
  }

  private void init() {
    setTypeface(Typeface.MONOSPACE);
    setIncludeFontPadding(false);
  }

  public void setText(CharSequence text, BufferType type) {
    dirty = !TextUtils.equals(getText(), text);
    super.setText(text, type);
    if (dirty) invalidate();
  }

  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    dirty = true;
  }

  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    super.onLayout(changed, left, top, right, bottom);
    if (changed) dirty = true;
  }

  protected void onDraw(Canvas canvas) {
    if (getWidth() <= 0 || getHeight() <= 0) return;
    if (dirty || dots == null || dots.getWidth() != getWidth() || dots.getHeight() != getHeight())
      rebuild();
    if (dots != null) {
      // ALPHA_8 stores only the dot mask. Supply the current text colour when compositing it;
      // drawing an alpha-only bitmap with a null paint renders black on some Android versions.
      tint.setColor(getCurrentTextColor());
      tint.setAlpha(Color.alpha(getCurrentTextColor()));
      canvas.drawBitmap(dots, 0, 0, tint);
    }
  }

  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    if (dots != null && !dots.isRecycled()) dots.recycle();
    dots = null;
    dirty = true;
  }

  @SuppressLint("WrongCall")
  private void rebuild() {
    dirty = false;
    int w = getWidth(), h = getHeight();
    int step = Math.max(3, Math.round(getResources().getDisplayMetrics().density * 1.35f));
    int sw = Math.max(1, (w + step - 1) / step), sh = Math.max(1, (h + step - 1) / step);
    Bitmap old = dots;
    Bitmap mask = Bitmap.createBitmap(sw, sh, Bitmap.Config.ALPHA_8);
    Canvas low = new Canvas(mask);
    low.scale(1f / step, 1f / step);
    super.onDraw(low);
    dots = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
    Canvas out = new Canvas(dots);
    Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    float radius = Math.max(1, step * .31f);
    dot.setColor(getCurrentTextColor());
    int[] row = new int[sw];
    for (int y = 0; y < sh; y++) {
      mask.getPixels(row, 0, sw, 0, y, sw, 1);
      for (int x = 0; x < sw; x++) {
        int color = row[x];
        if (Color.alpha(color) < 55) continue;
        dot.setAlpha(Color.alpha(color));
        out.drawCircle(x * step + step / 2f, y * step + step / 2f, radius, dot);
      }
    }
    mask.recycle();
    if (old != null && !old.isRecycled()) old.recycle();
  }
}
