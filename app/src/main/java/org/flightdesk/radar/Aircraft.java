package org.flightdesk.radar;

import java.util.*;
import org.json.*;

final class Aircraft {
  String hex = "", call = "", type = "", reg = "", country = "", squawk = "";
  double lat, lon, alt = Double.NaN, speed = Double.NaN, heading = Double.NaN, vr = Double.NaN;
  long seen;
  boolean ground, conflict;
  final List<double[]> trail = new ArrayList<>();

  static double distance(double la, double lo, double lb, double ln) {
    double p = Math.toRadians(lb - la), q = Math.toRadians(wrap(ln - lo));
    double a =
        Math.sin(p / 2) * Math.sin(p / 2)
            + Math.cos(Math.toRadians(la))
                * Math.cos(Math.toRadians(lb))
                * Math.sin(q / 2)
                * Math.sin(q / 2);
    return 6371 * 2 * Math.asin(Math.sqrt(Math.min(1, a)));
  }

  static double wrap(double v) {
    return ((v + 180) % 360 + 360) % 360 - 180;
  }

  double[] relative(double la, double lo) {
    return new double[] {
      wrap(lon - lo) * 111.32 * Math.cos(Math.toRadians(la)), (lat - la) * 110.574
    };
  }

  boolean emergency() {
    return squawk.equals("7500") || squawk.equals("7600") || squawk.equals("7700");
  }

  static List<Aircraft> parse(JSONObject j, String source, long now) throws Exception {
    List<Aircraft> out = new ArrayList<>();
    boolean sky = source.equals("OpenSky");
    if (j.has("error")) throw new Exception("服务拒绝访问，请检查数据源授权");
    JSONArray arr = j.optJSONArray(sky ? "states" : "ac");
    if (arr == null) {
      if (sky && j.has("states") && j.isNull("states")) return out;
      throw new Exception("数据源响应格式异常");
    }
    for (int i = 0; i < arr.length(); i++) {
      Aircraft a = new Aircraft();
      if (sky) {
        JSONArray x = arr.getJSONArray(i);
        if (x.isNull(5) || x.isNull(6)) continue;
        a.hex = x.optString(0);
        a.call = x.optString(1, "").trim();
        a.country = x.optString(2, "");
        a.lon = x.optDouble(5);
        a.lat = x.optDouble(6);
        a.alt = x.optDouble(7);
        a.ground = x.optBoolean(8);
        a.speed = x.optDouble(9) * 1.943844;
        a.heading = x.optDouble(10);
        a.vr = x.optDouble(11) * 196.8504;
        a.squawk = x.optString(14, "");
        a.seen = x.optLong(4, now / 1000) * 1000;
      } else {
        JSONObject x = arr.getJSONObject(i);
        if (!x.has("lat") || !x.has("lon")) continue;
        a.hex = x.optString("hex");
        a.call = x.optString("flight", "").trim();
        a.lat = x.optDouble("lat");
        a.lon = x.optDouble("lon");
        a.ground = "ground".equals(x.optString("alt_baro"));
        a.alt = a.ground ? 0 : x.optDouble("alt_baro") * 0.3048;
        a.speed = x.optDouble("gs");
        a.heading = x.optDouble("track");
        a.vr = x.optDouble("baro_rate");
        a.type = x.optString("t", "");
        a.reg = x.optString("r", "");
        a.squawk = x.optString("squawk", "");
        a.seen = now - (long) (x.optDouble("seen_pos", 0) * 1000);
      }
      if (a.call.equals("null") || a.call.isEmpty()) a.call = a.hex.toUpperCase(Locale.ROOT);
      if (Double.isFinite(a.lat)
          && Double.isFinite(a.lon)
          && Math.abs(a.lat) <= 90
          && Math.abs(a.lon) <= 180) out.add(a);
    }
    return out;
  }

  static void conflicts(List<Aircraft> a, long now) {
    for (Aircraft x : a) x.conflict = false;
    for (int i = 0; i < a.size(); i++)
      for (int k = i + 1; k < a.size(); k++) {
        Aircraft x = a.get(i), y = a.get(k);
        if (!x.ground
            && !y.ground
            && now - x.seen < 60000
            && now - y.seen < 60000
            && Math.abs(x.alt - y.alt) < 300
            && distance(x.lat, x.lon, y.lat, y.lon) < 5.5) x.conflict = y.conflict = true;
      }
  }
}
