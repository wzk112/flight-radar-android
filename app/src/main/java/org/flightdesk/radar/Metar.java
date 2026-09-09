package org.flightdesk.radar;

import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;

/** Structured AWC observations; unknown tokens stay available in the original report. */
final class Metar {
  final MainActivity host;
  final List<JSONObject> stations = new ArrayList<>();
  JSONObject report;
  String station = "", error = "";
  long next = 0, fetched = 0;
  boolean busy;
  String autoKey = "", autoStation = "";

  Metar(MainActivity host) {
    this.host = host;
    try {
      JSONArray all =
          new JSONArray(
              new String(host.readAsset("stations.json"), java.nio.charset.StandardCharsets.UTF_8));
      for (int i = 0; i < all.length(); i++) stations.add(all.getJSONObject(i));
    } catch (Exception ignored) {
    }
  }

  List<JSONObject> nearest(String query) {
    String q = query.trim().toUpperCase(Locale.ROOT);
    List<JSONObject> out = new ArrayList<>();
    for (JSONObject s : stations)
      if (q.isEmpty()
          || (s.optString("id") + " " + s.optString("iata") + " " + s.optString("name"))
              .toUpperCase(Locale.ROOT)
              .contains(q)) out.add(s);
    out.sort(
        Comparator.comparingDouble(
            s ->
                Aircraft.distance(
                    host.prefs.lat(), host.prefs.lon(), s.optDouble("lat"), s.optDouble("lon"))));
    return new ArrayList<>(out.subList(0, Math.min(20, out.size())));
  }

  String chosen() {
    String fixed = host.prefs.s("metarStation", "");
    if (!fixed.isEmpty()) return fixed;
    String key = host.prefs.lat() + ":" + host.prefs.lon();
    if (!key.equals(autoKey)) {
      List<JSONObject> near = nearest("");
      autoStation = near.isEmpty() ? "" : near.get(0).optString("id");
      autoKey = key;
    }
    return autoStation;
  }

  void refresh() {
    if (!host.active || busy) return;
    String id = chosen();
    long now = System.currentTimeMillis();
    if (!id.equals(station)) {
      station = id;
      report = null;
      error = "";
      next = 0;
      fetched = 0;
    }
    if (id.isEmpty() || now < next) return;
    busy = true;
    next = now + 300000;
    host.net.execute(
        () -> {
          JSONObject result = null;
          String failure = "";
          long delay = 300000;
          try {
            String raw =
                new String(
                    Api.request(
                        "https://aviationweather.gov/api/data/metar?ids="
                            + Api.enc(id)
                            + "&format=json",
                        "",
                        null,
                        ""),
                    java.nio.charset.StandardCharsets.UTF_8);
            JSONArray items = raw.isEmpty() ? new JSONArray() : new JSONArray(raw);
            for (int i = 0; i < items.length(); i++) {
              JSONObject item = items.getJSONObject(i);
              if (id.equals(item.optString("icaoId"))
                  && (result == null || item.optLong("obsTime") > result.optLong("obsTime")))
                result = item;
            }
            if (result == null) failure = "机场暂无近期 METAR，可选择其他机场";
          } catch (Exception e) {
            failure = "METAR 暂不可用，将自动重试";
            if (e instanceof Api.Failure) delay = Math.max(delay, ((Api.Failure) e).retry);
          }
          final JSONObject value = result;
          final String message = failure;
          final long wait = delay;
          host.runOnUiThread(
              () -> {
                busy = false;
                if (!id.equals(chosen())) {
                  next = 0;
                  return;
                }
                if (value != null) {
                  report = value;
                  fetched = System.currentTimeMillis();
                }
                error = message;
                next = System.currentTimeMillis() + wait;
                host.updateMetar();
              });
        });
  }

  String display() {
    String text =
        station.isEmpty()
            ? "正在选择最近的 METAR 机场…"
            : station
                + " · "
                + (host.prefs.s("metarStation", "").isEmpty() ? "距雷达中心最近的报告站" : "已选机场");
    if (report != null) {
      long age = Math.max(0, (System.currentTimeMillis() / 1000 - report.optLong("obsTime")) / 60);
      text +=
          "\n"
              + report.optString("name", station)
              + " · 观测于 "
              + age
              + " 分钟前"
              + (age > 90 ? " · 报文已陈旧" : "")
              + "\n\n"
              + compact(report)
              + "\n\n"
              + "原始报文\n" + report.optString("rawOb");
    } else text += "\n" + (busy ? "获取报文中…" : "暂无报文");
    if (!error.isEmpty()) text += "\n" + error;
    return text
        + "\n\nAWC / NOAA  /  RAW METAR  /  每 5 分钟检查 · "
        + (busy ? "更新中" : Math.max(0, (next - System.currentTimeMillis() + 999) / 1000) + " 秒后检查");
  }

  static String n(JSONObject o, String key, String unit) {
    double v = o.optDouble(key);
    return Double.isFinite(v) ? String.format(Locale.US, "%.0f%s", v, unit) : "未知";
  }

  static String compact(JSONObject o) {
    String raw = o.optString("rawOb");
    String direction = o.optString("wdir", "");
    String wind;
    if (o.optDouble("wspd") == 0) wind = "静风";
    else
      wind =
          (direction.equals("VRB") ? "风向不定" : direction.isEmpty() ? "风向未知" : direction + "°")
              + " "
              + n(o, "wspd", "KT")
              + (o.has("wgst") && !o.isNull("wgst") ? " 阵风" + n(o, "wgst", "KT") : "");
    String visibility = "未知";
    if (raw.matches(".*\\b9999\\b.*") || raw.contains("CAVOK")) visibility = "至少10KM";
    else {
      double miles = o.optDouble("visib");
      if (Double.isFinite(miles)) visibility = String.format(Locale.US, "%.1fKM", miles * 1.60934);
    }
    String cloud = "未知";
    JSONArray clouds = o.optJSONArray("clouds");
    if (clouds != null && clouds.length() > 0) {
      JSONObject c = clouds.optJSONObject(0);
      if (c != null) {
        Map<String, String> names =
            Map.of(
                "FEW", "少云",
                "SCT", "疏云",
                "BKN", "多云",
                "OVC", "阴天",
                "CLR", "晴空",
                "SKC", "晴空",
                "VV", "垂直能见度");
        cloud = names.getOrDefault(c.optString("cover"), c.optString("cover", "未知"));
        if (c.has("base") && !c.isNull("base")) cloud += " " + n(c, "base", "FT");
      }
    } else if (raw.contains("CAVOK")) cloud = "无低云";
    StringBuilder out =
        new StringBuilder(
            "中文  "
                + wind
                + "  能见度 "
                + visibility
                + "\n云况  "
                + cloud
                + "  温/露 "
                + n(o, "temp", "°C")
                + "/"
                + n(o, "dewp", "°C")
                + "\nCAT "
                + o.optString("fltCat", "UNKNOWN")
                + "  QNH "
                + n(o, "altim", "HPA"));
    String wx = o.optString("wxString", "");
    if (!wx.isEmpty()) out.append("\n天气  ").append(weather(wx));
    return out.toString();
  }

  static String decode(JSONObject o) {
    String raw = o.optString("rawOb");
    SimpleDateFormat utc = new SimpleDateFormat("MM-dd HH:mm 'UTC'", Locale.US);
    utc.setTimeZone(TimeZone.getTimeZone("UTC"));
    String wind = o.optString("wdir", "");
    if (wind.equals("VRB")) wind = "风向不定";
    else if (o.optDouble("wspd") == 0) wind = "静风";
    else wind = wind.isEmpty() ? "风向未知" : wind + "° 来风";
    StringBuilder s =
        new StringBuilder(
            "中文解读\n观测时间 "
                + (o.optLong("obsTime") > 0
                    ? utc.format(new Date(o.optLong("obsTime") * 1000))
                    : "未知")
                + "\n风："
                + wind
                + "，"
                + n(o, "wspd", " kt"));
    if (o.has("wgst") && !o.isNull("wgst")) s.append("，阵风 ").append(n(o, "wgst", " kt"));
    String visibility = o.optString("visib", "");
    if (raw.matches(".*\\b9999\\b.*")) visibility = "至少 10 km";
    else if (!visibility.isEmpty()) visibility += " statute miles（英里）";
    else visibility = "未知";
    s.append("\n能见度：").append(visibility);
    if (raw.contains("CAVOK")) s.append("；CAVOK：能见度至少 10 km、无重要天气及低云");
    s.append("\n温度 / 露点：").append(n(o, "temp", "°C")).append(" / ").append(n(o, "dewp", "°C"));
    s.append("\n海平面气压：").append(n(o, "altim", " hPa"));
    JSONArray clouds = o.optJSONArray("clouds");
    if (clouds != null)
      for (int i = 0; i < clouds.length(); i++) {
        JSONObject c = clouds.optJSONObject(i);
        if (c == null) continue;
        String cover = c.optString("cover");
        String cn =
            Map.of(
                    "FEW",
                    "少云（1–2/8）",
                    "SCT",
                    "疏云（3–4/8）",
                    "BKN",
                    "多云（5–7/8）",
                    "OVC",
                    "阴天（8/8）",
                    "CLR",
                    "晴空",
                    "SKC",
                    "晴空",
                    "NSC",
                    "无重要云",
                    "NCD",
                    "未探测到云",
                    "VV",
                    "垂直能见度")
                .getOrDefault(cover, cover);
        s.append("\n云：").append(cn);
        if (!c.isNull("base") && c.has("base")) s.append("，距地 ").append(n(c, "base", " ft"));
      }
    String wx = o.optString("wxString", "");
    if (!wx.isEmpty()) s.append("\n天气：").append(weather(wx));
    if (raw.contains(" AUTO ")) s.append("\n自动观测报文");
    s.append("\n未解读的趋势、跑道视程及备注请参照原文。");
    return s.toString();
  }

  static String weather(String wx) {
    Map<String, String> codes =
        Map.ofEntries(
            Map.entry("RA", "雨"),
            Map.entry("SN", "雪"),
            Map.entry("DZ", "毛毛雨"),
            Map.entry("TS", "雷暴"),
            Map.entry("SH", "阵性"),
            Map.entry("FG", "雾"),
            Map.entry("BR", "轻雾"),
            Map.entry("HZ", "霾"),
            Map.entry("FU", "烟"),
            Map.entry("GR", "冰雹"),
            Map.entry("GS", "小冰雹"),
            Map.entry("FZ", "过冷"),
            Map.entry("BL", "吹"),
            Map.entry("DU", "尘"),
            Map.entry("SA", "沙"),
            Map.entry("SQ", "飑"),
            Map.entry("FC", "漏斗云"),
            Map.entry("VC", "附近"),
            Map.entry("MI", "浅"),
            Map.entry("BC", "碎片状"),
            Map.entry("PL", "冰粒"));
    StringBuilder out = new StringBuilder();
    for (String token : wx.split("\\s+")) {
      String t = token;
      out.append(t.startsWith("-") ? "小" : t.startsWith("+") ? "强" : "");
      t = t.replaceFirst("^[+-]", "");
      for (int i = 0; i < t.length(); i += 2) {
        String part = t.substring(i, Math.min(i + 2, t.length()));
        out.append(codes.getOrDefault(part, part));
      }
      out.append(" ");
    }
    return out.toString().trim() + "（" + wx + "）";
  }
}
