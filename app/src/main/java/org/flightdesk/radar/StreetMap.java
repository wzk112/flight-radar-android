package org.flightdesk.radar;

import android.graphics.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** Visible-viewport OSM tiles only; 7-day cache and conditional revalidation. */
final class StreetMap {
  static final class Tile {
    final Bitmap image;
    final int x, y, z;

    Tile(Bitmap b, int x, int y, int z) {
      image = b;
      this.x = x;
      this.y = y;
      this.z = z;
    }
  }

  final MainActivity a;
  List<Tile> tiles = Collections.emptyList();
  String key = "";
  boolean busy;
  long retryAt;
  String status = "";
  final Paint paint = new Paint(3);

  StreetMap(MainActivity a) {
    this.a = a;
    paint.setColorFilter(
        new ColorMatrixColorFilter(
            new float[] {
              -.12f, -.12f, -.12f, 0, 104, -.16f, -.16f, -.16f, 0, 143, -.12f, -.12f, -.12f, 0, 110,
              0, 0, 0, 1, 0
            }));
  }

  static double lon(double x, int z) {
    return x / (1 << z) * 360 - 180;
  }

  static double lat(double y, int z) {
    return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y / (1 << z)))));
  }

  static double tileY(double lat, int z) {
    double r = Math.toRadians(Math.max(-85.05, Math.min(85.05, lat)));
    return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * (1 << z);
  }

  void refresh() {
    if (System.currentTimeMillis() < retryAt
        || busy
        || !a.active
        || !a.page.equals("雷达")
        || !a.prefs.b("streets", true)
        || !a.prefs.b("map", true)) return;
    double la = a.prefs.lat(), lo = a.prefs.lon(), range = a.prefs.range();
    int z =
        Math.max(
            2,
            Math.min(
                14,
                1
                    + (int)
                        Math.floor(
                            Math.log(40075 * Math.max(.05, Math.cos(Math.toRadians(la))) / range)
                                / Math.log(2))));
    double xRange = range, yRange = range;
    if (a.radar != null && a.radar.r > 0) {
      xRange = range * Math.min(1, a.radar.getWidth() / 2.0 / a.radar.r);
      yRange = range * Math.min(1, a.radar.getHeight() / 2.0 / a.radar.r);
    }
    final double visibleX = xRange, visibleY = yRange;
    String wanted = la + ":" + lo + ":" + z + ":" + range + ":" + visibleX + ":" + visibleY;
    if (wanted.equals(key)) return;
    busy = true;
    status = "街道底图加载中";
    int generation = a.generation;
    a.net.execute(
        () -> {
          List<Tile> result = new ArrayList<>();
          boolean failed = false;
          try {
            double dlat = visibleY / 110.574,
                dlon = visibleX / (111.32 * Math.max(.05, Math.cos(Math.toRadians(la))));
            int n = 1 << z;
            int xmin = (int) Math.floor((lo - dlon + 180) / 360 * n),
                xmax = (int) Math.floor((lo + dlon + 180) / 360 * n),
                ymin = Math.max(0, (int) Math.floor(tileY(la + dlat, z))),
                ymax = Math.min(n - 1, (int) Math.floor(tileY(la - dlat, z)));
            int count = 0;
            for (int y = ymin; y <= ymax; y++)
              for (int x = xmin; x <= xmax; x++) {
                if (!a.active || a.generation != generation || !a.page.equals("雷达"))
                  throw new IOException("view changed");
                if (++count > 64) continue;
                int xx = ((x % n) + n) % n;
                result.add(new Tile(fetch(z, xx, y), x, y, z));
              }
          } catch (Exception e) {
            failed = true;
          }
          boolean error = failed;
          a.runOnUiThread(
              () -> {
                busy = false;
                if (generation == a.generation) {
                  if (!result.isEmpty()) tiles = result;
                  key = error ? "" : wanted;
                  retryAt = error ? System.currentTimeMillis() + 60000 : 0;
                  status = error ? "街道底图未完整加载，稍后重试" : "";
                  if (a.radar != null) a.radar.invalidate();
                }
              });
        });
  }

  Bitmap fetch(int z, int x, int y) throws Exception {
    File dir = new File(a.getCacheDir(), "osm");
    dir.mkdirs();
    File f = new File(dir, z + "_" + x + "_" + y + ".png");
    if (f.exists() && System.currentTimeMillis() - f.lastModified() < 7L * 86400000) {
      Bitmap b = BitmapFactory.decodeFile(f.toString());
      if (b != null) return b;
    }
    HttpURLConnection c =
        (HttpURLConnection)
            new URL("https://tile.openstreetmap.org/" + z + "/" + x + "/" + y + ".png")
                .openConnection();
    c.setConnectTimeout(10000);
    c.setReadTimeout(15000);
    c.setRequestProperty(
        "User-Agent", "FlightRadarAndroid/0.1 (org.flightdesk.radar; personal viewer)");
    if (f.exists()) c.setIfModifiedSince(f.lastModified());
    try {
      int status = c.getResponseCode();
      if (status == 304) {
        f.setLastModified(System.currentTimeMillis());
        return BitmapFactory.decodeFile(f.toString());
      }
      if (status != 200) throw new IOException("Tile HTTP " + status);
      byte[] data;
      try (InputStream in = c.getInputStream();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] b = new byte[8192];
        int k;
        while ((k = in.read(b)) > 0) {
          if (out.size() + k > 2000000) throw new IOException("oversized tile");
          out.write(b, 0, k);
        }
        data = out.toByteArray();
      }
      Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
      if (bitmap == null) throw new IOException("tile decode");
      java.nio.file.Files.write(f.toPath(), data);
      return bitmap;
    } finally {
      c.disconnect();
    }
  }

  void draw(Canvas canvas, RadarView view) {
    int steps = 8;
    for (Tile t : tiles) {
      float[] mesh = new float[(steps + 1) * (steps + 1) * 2];
      int k = 0;
      for (int y = 0; y <= steps; y++)
        for (int x = 0; x <= steps; x++) {
          PointF point =
              view.point(lat(t.y + y / (double) steps, t.z), lon(t.x + x / (double) steps, t.z));
          mesh[k++] = point.x;
          mesh[k++] = point.y;
        }
      canvas.drawBitmapMesh(t.image, steps, steps, mesh, 0, null, 0, paint);
    }
  }
}
