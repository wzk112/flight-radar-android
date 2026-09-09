package org.flightdesk.radar;

import android.content.Context;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.zip.CRC32;

final class MapData {
  static final class Line {
    float[] pts;
    int kind;
    String name = "";

    Line(float[] p, int k) {
      pts = p;
      kind = k;
    }
  }

  static final class Mark {
    String name;
    float lat, lon;
    boolean fix;

    Mark(String n, float a, float o, boolean f) {
      name = n;
      lat = a;
      lon = o;
      fix = f;
    }
  }

  final List<Line> lines = new ArrayList<>();
  final List<Mark> marks = new ArrayList<>();

  static MapData parse(byte[] bytes) throws Exception {
    ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    if (bytes.length < 64
        || b.getInt(0) != 0x544d5246
        || b.getShort(4) != 1
        || (b.getShort(6) & 1) == 0) throw new IOException("地图格式异常");
    CRC32 crc = new CRC32();
    crc.update(bytes, 64, bytes.length - 64);
    if (crc.getValue() != Integer.toUnsignedLong(b.getInt(8))) throw new IOException("地图校验失败");
    MapData d = new MapData();
    for (int sec = 0; sec < 5; sec++) {
      int off = b.getInt(24 + sec * 8), len = b.getInt(28 + sec * 8);
      if (off < 0 || len < 0 || (long) off + len > bytes.length - 64) throw new IOException("地图越界");
      int start = 64 + off, end = start + len;
      b.position(start);
      if (sec == 0) {
        List<Float> p = new ArrayList<>();
        int kind = 0;
        while (b.position() + 8 <= end) {
          float la = b.getFloat(), lo = b.getFloat();
          if (Float.isNaN(la)) {
            add(d, p, kind);
            p.clear();
            kind = (int) lo;
          } else {
            p.add(la);
            p.add(lo);
          }
        }
        add(d, p, kind);
      }
      if (sec == 1 || sec == 3) {
        int size = sec == 1 ? 13 : 14, n = sec == 1 ? 5 : 6;
        if (len % size != 0) throw new IOException("地图记录异常");
        while (b.position() < end) {
          byte[] name = new byte[n];
          b.get(name);
          d.marks.add(
              new Mark(
                  new String(name, java.nio.charset.StandardCharsets.US_ASCII).replace("\0", ""),
                  b.getFloat(),
                  b.getFloat(),
                  sec == 3));
        }
      }
      if (sec == 2) {
        if (len % 32 != 0) throw new IOException("跑道记录异常");
        while (b.position() < end) {
          float[] r = new float[8];
          for (int k = 0; k < 8; k++) r[k] = b.getFloat();
          d.lines.add(new Line(Arrays.copyOfRange(r, 0, 4), 3));
          d.lines.add(new Line(Arrays.copyOfRange(r, 4, 8), 4));
        }
      }
      if (sec == 4 && len >= 4) {
        int n = Short.toUnsignedInt(b.getShort()), sn = Short.toUnsignedInt(b.getShort());
        int rec = b.position(), str = rec + n * 8, pos = str + sn;
        if (pos > end) throw new IOException("空域记录异常");
        for (int k = 0; k < n; k++) {
          int r = rec + k * 8,
              cls = bytes[r] & 255,
              no = Short.toUnsignedInt(b.getShort(r + 2)),
              np = Short.toUnsignedInt(b.getShort(r + 4));
          if (no >= sn || (long) pos + np * 8 > end) throw new IOException("空域越界");
          float[] p = new float[np * 2];
          b.position(pos);
          for (int j = 0; j < p.length; j++) p[j] = b.getFloat();
          pos = b.position();
          Line l = new Line(p, 5 + cls);
          int z = str + no;
          while (z < str + sn && bytes[z] != 0) z++;
          l.name =
              new String(bytes, str + no, z - str - no, java.nio.charset.StandardCharsets.UTF_8);
          d.lines.add(l);
        }
      }
    }
    return d;
  }

  static void add(MapData d, List<Float> p, int k) {
    if (p.size() < 4) return;
    float[] a = new float[p.size()];
    for (int i = 0; i < a.length; i++) a[i] = p.get(i);
    d.lines.add(new Line(a, k));
  }

  static MapData load(Context c, double lat, double lon, double range) throws Exception {
    MapData all = new MapData();
    int level = range > 250 ? 1 : range > 100 ? 2 : 3;
    double dl = range / 110.574,
        dx = range / (111.32 * Math.max(.05, Math.cos(Math.toRadians(lat))));
    int count = 0;
    File dir = new File(c.getCacheDir(), "maps");
    dir.mkdirs();
    for (int la = (int) Math.floor((lat - dl) / 10) * 10; la <= lat + dl; la += 10)
      for (int lo = (int) Math.floor((lon - dx) / 10) * 10; lo <= lon + dx; lo += 10) {
        if (la < -80 || la >= 80 || count++ >= 24) continue;
        int wl = (int) Aircraft.wrap(lo);
        String name =
            String.format(
                Locale.US,
                "L%d_%s%02d%s%03d.bin",
                level,
                la >= 0 ? "N" : "S",
                Math.abs(la),
                wl >= 0 ? "E" : "W",
                Math.abs(wl));
        File f = new File(dir, name);
        byte[] bytes;
        try {
          if (f.exists()) bytes = java.nio.file.Files.readAllBytes(f.toPath());
          else {
            bytes =
                Api.request(
                    "https://delphicchen.github.io/flight-radar-maps/v1/L"
                        + level
                        + "/"
                        + name.substring(name.indexOf('_') + 1),
                    "",
                    null,
                    "");
            parse(bytes);
            java.nio.file.Files.write(f.toPath(), bytes);
          }
          MapData d = parse(bytes);
          all.lines.addAll(d.lines);
          all.marks.addAll(d.marks);
        } catch (Api.Failure e) {
          if (e.status != 404) throw e;
        }
      }
    return all;
  }
}
