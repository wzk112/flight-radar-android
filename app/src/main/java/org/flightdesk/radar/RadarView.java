package org.flightdesk.radar;

import android.content.*;
import android.graphics.*;
import android.view.*;
import java.util.*;

final class RadarView extends View {
  final MainActivity host;
  final Paint p = new Paint(3);
  float cx, cy, r;
  boolean running = false;
  String selected = "";
  final Map<String, RectF> labelHits = new HashMap<>();
  final ScaleGestureDetector scaler;
  boolean zoomed = false;
  double zoomRange;
  double previewRange = Double.NaN;

  double displayRange() {
    return Double.isFinite(previewRange) ? previewRange : host.prefs.range();
  }

  final Map<String, PointF> hits = new HashMap<>();

  RadarView(MainActivity a) {
    super(a);
    host = a;
    scaler =
        new ScaleGestureDetector(
            a,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
              public boolean onScaleBegin(ScaleGestureDetector d) {
                zoomed = true;
                zoomRange = displayRange();
                return true;
              }

              public boolean onScale(ScaleGestureDetector d) {
                double factor = d.getScaleFactor();
                if (!Double.isFinite(factor) || factor <= 0) return false;
                zoomRange = Math.max(10, Math.min(460, zoomRange / factor));
                previewRange = zoomRange;
                invalidate();
                return true;
              }

              public void onScaleEnd(ScaleGestureDetector d) {
                final double range = zoomRange;
                post(
                    () -> {
                      if (host.radar == RadarView.this && isAttachedToWindow()) {
                        host.commitRange(range);
                        previewRange = Double.NaN;
                        invalidate();
                      }
                    });
              }
            });
    scaler.setQuickScaleEnabled(false);
    setContentDescription("实时航班雷达，点击飞机查看详情");
    setLayerType(View.LAYER_TYPE_HARDWARE, null);
  }

  void line(Canvas c, float x, float y, float a, float b, int color, float width) {
    p.setColor(color);
    p.setStrokeWidth(width);
    p.setStyle(Paint.Style.STROKE);
    c.drawLine(x, y, a, b, p);
    p.setStyle(Paint.Style.FILL);
  }

  void txt(Canvas c, String s, float x, float y, float size, int color) {
    p.setColor(color);
    p.setTextSize(size);
    p.setTypeface(Typeface.MONOSPACE);
    p.setStyle(Paint.Style.FILL);
    c.drawText(s, x, y, p);
  }

  PointF point(double la, double lo) {
    return new PointF(
        cx
            + (float)
                (Aircraft.wrap(lo - host.prefs.lon())
                    * 111.32
                    * Math.cos(Math.toRadians(host.prefs.lat()))
                    / displayRange()
                    * r),
        cy - (float) ((la - host.prefs.lat()) * 110.574 / displayRange() * r));
  }

  protected void onDraw(Canvas c) {
    super.onDraw(c);
    c.drawColor(Color.rgb(3, 10, 10));
    float w = getWidth(), h = getHeight();
    cx = w / 2;
    cy = h / 2;
    r = Math.max(1, (float) Math.hypot(w, h) / 2 - 16 * getResources().getDisplayMetrics().density);
    int green = Color.rgb(93, 216, 165), grid = Color.rgb(21, 57, 47);
    float dp = getResources().getDisplayMetrics().density;
    c.save();
    c.clipRect(0, 0, w, h);
    if (host.prefs.b("map", true) && host.prefs.b("streets", true)) host.streets.draw(c, this);
    for (float x = cx % (r / 4); x < w; x += r / 4) line(c, x, 0, x, h, grid, dp * .5f);
    for (float y = cy % (r / 4); y < h; y += r / 4) line(c, 0, y, w, y, grid, dp * .5f);
    if (host.prefs.b("echo", false) && host.echo != null && !Double.isFinite(previewRange)) {
      double size = host.echoKm / (2 * displayRange()) * r * 2;
      p.setAlpha(135);
      c.drawBitmap(
          host.echo,
          null,
          new RectF(
              cx - (float) size / 2,
              cy - (float) size / 2,
              cx + (float) size / 2,
              cy + (float) size / 2),
          p);
      p.setAlpha(255);
    }
    if (host.prefs.b("map", true) && host.map != null) {
      for (MapData.Line l : host.map.lines) {
        if (l.kind < 3 && host.prefs.b("streets", true) && !host.streets.tiles.isEmpty()) continue;
        if (l.kind == 4 && !host.prefs.b("extensions", false)) continue;
        if (l.kind >= 5 && !host.prefs.b("airspace", true)
            || l.kind == 3 && !host.prefs.b("runways", true)
            || l.kind == 4 && !host.prefs.b("runways", true)) continue;
        Path path = new Path();
        for (int k = 0; k + 1 < l.pts.length; k += 2) {
          PointF z = point(l.pts[k], l.pts[k + 1]);
          if (k == 0) path.moveTo(z.x, z.y);
          else path.lineTo(z.x, z.y);
        }
        p.setColor(l.kind >= 5 ? 0xff394a5e : l.kind >= 3 ? 0xff547666 : 0xff294b41);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(l.kind == 3 ? 2 * dp : .6f * dp);
        c.drawPath(path, p);
      }
      p.setStyle(Paint.Style.FILL);
      for (MapData.Mark m : host.map.marks) {
        if (m.fix && !host.prefs.b("fixes", false) || !m.fix && !host.prefs.b("airports", true))
          continue;
        PointF z = point(m.lat, m.lon);
        p.setColor(0xff486958);
        c.drawCircle(z.x, z.y, 2 * dp, p);
        txt(c, m.name, z.x + 4 * dp, z.y - 3 * dp, 8 * dp, 0xff668579);
      }
    }
    long now = System.currentTimeMillis();
    c.restore();
    hits.clear();
    labelHits.clear();
    boolean atc = host.prefs.b("atc", true);
    List<RectF> occupied = new ArrayList<>();
    List<Aircraft> aircraft = new ArrayList<>(host.aircraft);
    aircraft.sort(Comparator.comparingInt(a -> a.hex.equals(selected) ? 0 : a.ground ? 2 : 1));
    for (Aircraft a : aircraft) {
      if (a.ground && !host.prefs.b("ground", true)) continue;
      PointF z = point(a.lat, a.lon);
      if (z.x < 0 || z.x > w || z.y < 0 || z.y > h) continue;
      hits.put(a.hex, z);
      long staleAfter = Math.max(90000L, host.prefs.i("poll", 15) * 3000L);
      boolean stale = now - a.seen > staleAfter;
      int color =
          a.emergency() || atc && a.conflict
              ? 0xffff647c
              : a.hex.equals(selected) ? 0xffffcf73 : stale ? 0xff52665e : green;
      if (atc) {
        for (int k = 0; k < a.trail.size(); k++) {
          PointF t = point(a.trail.get(k)[0], a.trail.get(k)[1]);
          p.setColor(color);
          p.setAlpha(40 + 120 * (k + 1) / Math.max(1, a.trail.size()));
          c.drawCircle(t.x, t.y, 1.5f * dp, p);
        }
        p.setAlpha(255);
        if (!stale && Double.isFinite(a.heading) && Double.isFinite(a.speed)) {
          double dist = a.speed * 1.852 / 30 / displayRange() * r;
          line(
              c,
              z.x,
              z.y,
              z.x + (float) (Math.sin(Math.toRadians(a.heading)) * dist),
              z.y - (float) (Math.cos(Math.toRadians(a.heading)) * dist),
              color,
              dp * .7f);
        }
      }
      if (Double.isFinite(a.heading)) {
        c.save();
        c.rotate((float) a.heading, z.x, z.y);
        p.setColor(color);
        Path plane = new Path();
        plane.moveTo(z.x, z.y - 7 * dp);
        plane.lineTo(z.x + 2 * dp, z.y - 1 * dp);
        plane.lineTo(z.x + 7 * dp, z.y + 3 * dp);
        plane.lineTo(z.x + 1 * dp, z.y + 2 * dp);
        plane.lineTo(z.x, z.y + 6 * dp);
        plane.lineTo(z.x - 1 * dp, z.y + 2 * dp);
        plane.lineTo(z.x - 7 * dp, z.y + 3 * dp);
        plane.lineTo(z.x - 2 * dp, z.y - dp);
        plane.close();
        c.drawPath(plane, p);
        c.restore();
      } else {
        p.setColor(color);
        c.drawCircle(z.x, z.y, 4 * dp, p);
      }
      float tx = 0, ty = 0;
      boolean placed = false;
      RectF chosen = null;
      for (int slot = 0; slot < 40; slot++) {
        int row = slot / 4, shift = (row + 1) / 2 * (row % 2 == 0 ? 1 : -1);
        float xx = z.x + (slot % 4 < 2 ? 16 : -138) * dp,
            yy = z.y + shift * 49 * dp + (slot % 2 == 0 ? 0 : 22) * dp;
        RectF box = new RectF(xx - 5 * dp, yy - 16 * dp, xx + 123 * dp, yy + 30 * dp);
        boolean fits =
            box.left > 4 * dp
                && box.right < getWidth() - 4 * dp
                && box.top > 4 * dp
                && box.bottom < getHeight() - 4 * dp;
        for (RectF taken : occupied) if (RectF.intersects(box, taken)) fits = false;
        if (fits) {
          chosen = box;
          occupied.add(box);
          tx = xx;
          ty = yy;
          placed = true;
          break;
        }
      }
      if (placed) {
        labelHits.put(a.hex, chosen);
        line(
            c,
            z.x,
            z.y,
            Math.max(chosen.left, Math.min(z.x, chosen.right)),
            Math.max(chosen.top, Math.min(z.y, chosen.bottom)),
            color,
            dp * .5f);
        p.setColor(a.hex.equals(selected) ? 0xee173326 : 0xdd07130e);
        c.drawRoundRect(chosen, 4 * dp, 4 * dp, p);
        txt(c, a.call + (stale ? " OLD" : ""), tx, ty - 3 * dp, 11 * dp, color);
        txt(c, host.routes.label(a.call), tx, ty + 10 * dp, 11 * dp, color);
        txt(
            c,
            MainActivity.fmt(a.alt / .3048, "ft") + " · " + MainActivity.fmt(a.speed, "kt"),
            tx,
            ty + 23 * dp,
            10 * dp,
            color);
      }
    }
    txt(c, "N ↑  /  ATC", 10 * dp, 18 * dp, 11 * dp, green);
    float scaleWidth = r / 4;
    line(c, w - scaleWidth - 16 * dp, h - 22 * dp, w - 16 * dp, h - 22 * dp, green, dp);
    txt(
        c,
        String.format(Locale.US, "%.1f km", displayRange() / 4),
        w - scaleWidth - 16 * dp,
        h - 27 * dp,
        10 * dp,
        green);
    p.setColor(green);
    c.drawCircle(cx, cy, 3 * dp, p);
    if (aircraft.isEmpty()) txt(c, "等待此范围内的航班数据", cx - 90 * dp, cy + 28 * dp, 12 * dp, 0xff749a87);
    if (host.prefs.b("map", true) && host.prefs.b("streets", true) && !host.streets.tiles.isEmpty())
      txt(c, "© OpenStreetMap contributors", 8 * dp, getHeight() - 8 * dp, 10 * dp, 0xff9bc1ad);
  }

  List<Aircraft> targets(float x, float y) {
    List<Aircraft> candidates = new ArrayList<>();
    for (Aircraft a : host.aircraft) {
      RectF label = labelHits.get(a.hex);
      if (label != null && label.contains(x, y)) {
        candidates.add(a);
        return candidates;
      }
    }
    float radius = 32 * getResources().getDisplayMetrics().density;
    for (Aircraft a : host.aircraft) {
      PointF hit = hits.get(a.hex);
      if (hit != null && Math.hypot(x - hit.x, y - hit.y) <= radius) candidates.add(a);
    }
    candidates.sort(
        Comparator.comparingDouble(
            a -> {
              PointF hit = hits.get(a.hex);
              return Math.hypot(x - hit.x, y - hit.y);
            }));
    return candidates;
  }

  public boolean onTouchEvent(MotionEvent e) {
    scaler.onTouchEvent(e);
    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) zoomed = false;
    if (e.getActionMasked() == MotionEvent.ACTION_UP && !zoomed) {
      performClick();
      List<Aircraft> choices = targets(e.getX(), e.getY());
      if (choices.isEmpty() || (choices.size() == 1 && choices.get(0).hex.equals(selected))) {
        selected = "";
        invalidate();
      } else {
        host.chooseAircraft(choices);
      }
      return true;
    }
    return true;
  }

  public boolean performClick() {
    super.performClick();
    return true;
  }
}
