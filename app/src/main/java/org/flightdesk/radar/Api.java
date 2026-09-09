package org.flightdesk.radar;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

final class Api {
  static final class Failure extends IOException {
    final int status;
    final long retry;

    Failure(int s, long r) {
      super("HTTP " + s + (s == 429 ? " · 请求限流" : s == 401 || s == 403 ? " · 访问未获授权" : ""));
      status = s;
      retry = r;
    }
  }

  final Map<String, Long> blocked = new HashMap<>();
  static final class Cooling extends IOException {
    Cooling(String reason) { super(reason); }
  }

  long readyAt(Prefs p, String source) {
    return Math.max((long) p.n("cooldown_" + source, 0), blocked.getOrDefault(source, 0L));
  }

  static long retryDelay(int failures) {
    return Math.min(60000L, 15000L * (1L << Math.min(2, Math.max(0, failures - 1))));
  }
  static long limitedDelay(int count, long serverWait) {
    return Math.max(serverWait, Math.min(900000L, 60000L * (1L << Math.min(4, Math.max(0, count - 1)))));
  }
  String token = "";
  long expires = 0;

  static byte[] request(String url, String auth, String body, String type) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setInstanceFollowRedirects(false);
    c.setConnectTimeout(12000);
    c.setReadTimeout(18000);
    c.setRequestProperty("User-Agent", "FlightRadarAndroid/0.1 (personal non-commercial)");
    try {
      if (!auth.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + auth);
      if (body != null) {
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", type);
        try (OutputStream o = c.getOutputStream()) {
          o.write(body.getBytes(StandardCharsets.UTF_8));
        }
      }
      int s = c.getResponseCode();
      if (s < 200 || s >= 300) {
        long retry = 60000;
        for (String h : new String[] {"Retry-After", "X-Rate-Limit-Retry-After-Seconds"})
          try {
            retry = Math.max(retry, Long.parseLong(c.getHeaderField(h)) * 1000);
          } catch (Exception ignored) {
            try {
              long date = c.getHeaderFieldDate(h, -1);
              if (date > 0) retry = Math.max(retry, date - System.currentTimeMillis());
            } catch (Exception ignoredDate) {}
          }
        throw new Failure(s, retry);
      }
      try (InputStream in = c.getInputStream();
          ByteArrayOutputStream o = new ByteArrayOutputStream()) {
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) {
          if (o.size() + n > 12000000) throw new IOException("响应过大");
          o.write(b, 0, n);
        }
        return o.toByteArray();
      }
    } finally {
      c.disconnect();
    }
  }

  static JSONObject json(String url, String token, String body) throws Exception {
    return new JSONObject(
        new String(request(url, token, body, "application/json"), StandardCharsets.UTF_8));
  }

  static String enc(String s) {
    try {
      return URLEncoder.encode(s, "UTF-8");
    } catch (java.io.UnsupportedEncodingException e) {
      throw new AssertionError(e);
    }
  }

  List<Aircraft> flights(Prefs p, String source) throws Exception {
    long now = System.currentTimeMillis();
    long persisted = (long) p.n("cooldown_" + source, 0);
    if (Math.max(persisted, blocked.getOrDefault(source, 0L)) > now)
      throw new Cooling(p.s("cooldownReason_" + source, "等待请求间隔结束"));
    p.put("cooldown_" + source, "" + (now + Math.max(30, p.i("poll", 30)) * 1000L));
    p.put("cooldownReason_" + source, "等待请求间隔结束");
    try {
      String url, auth = "";
      if (source.equals("OpenSky")) {
        String id = p.secret("clientId"), sec = p.secret("clientSecret");
        if (!id.isEmpty() && !sec.isEmpty()) {
          if (now > expires) {
            JSONObject t =
                new JSONObject(
                    new String(
                        request(
                            "https://auth.opensky-network.org/auth/realms/opensky-network/protocol/openid-connect/token",
                            "",
                            "grant_type=client_credentials&client_id="
                                + enc(id)
                                + "&client_secret="
                                + enc(sec),
                            "application/x-www-form-urlencoded"),
                        StandardCharsets.UTF_8));
            token = t.getString("access_token");
            expires = now + Math.max(0, t.optLong("expires_in", 300) - 30) * 1000;
          }
          auth = token;
        }
        double d = p.range() / 110.574,
            e =
                Math.min(
                    180, p.range() / (111.32 * Math.max(.05, Math.cos(Math.toRadians(p.lat())))));
        double lo = p.lon() - e, hi = p.lon() + e;
        if (lo < -180 || hi > 180) {
          lo = -180;
          hi = 180;
        }
        url =
            String.format(
                Locale.US,
                "https://opensky-network.org/api/states/all?lamin=%.5f&lomin=%.5f&lamax=%.5f&lomax=%.5f",
                Math.max(-90, p.lat() - d),
                lo,
                Math.min(90, p.lat() + d),
                hi);
      } else
        url =
            String.format(
                Locale.US,
                "https://api.%s/v2/point/%.5f/%.5f/%.1f",
                source,
                p.lat(),
                p.lon(),
                Math.min(250, p.range() / 1.852));
      List<Aircraft> a = Aircraft.parse(json(url, auth, null), source, now);
      a.removeIf(x -> Aircraft.distance(p.lat(), p.lon(), x.lat, x.lon) > p.range());
      a.sort(Comparator.comparingDouble(x -> Aircraft.distance(p.lat(), p.lon(), x.lat, x.lon)));
      p.put("rateFailures_" + source, 0);
      return new ArrayList<>(a.subList(0, Math.min(40, a.size())));
    } catch (Failure f) {
      if (f.status == 401) expires = 0;
      long wait = 30000;
      String reason = "HTTP " + f.status + " · 请求失败";
      if (f.status == 429) {
        int count = Math.min(20, p.i("rateFailures_" + source, 0) + 1);
        p.put("rateFailures_" + source, count);
        wait = limitedDelay(count, f.retry);
        reason = "HTTP 429 · 限流冷却";
      } else if (f.status == 401 || f.status == 403) {
        wait = Math.max(300000, f.retry);
        reason = "HTTP " + f.status + " · 请检查数据源授权";
      } else if (f.status == 503) wait = Math.max(30000, f.retry);
      long until = System.currentTimeMillis() + wait;
      p.put("cooldownReason_" + source, reason);
      blocked.put(source, until);
      p.put("cooldown_" + source, "" + until);
      throw f;
    }
  }
}
