package org.flightdesk.radar;

import android.graphics.*;
import android.view.View;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.json.*;
import java.util.concurrent.*;
import java.lang.ref.WeakReference;

/** Local airline identity library plus best-effort cached artwork lookup. */
final class AirlineLogos {
  static final class Brand {
    final String iata, name;
    final int color;

    Brand(String iata, String name, int color) {
      this.iata = iata;
      this.name = name;
      this.color = color;
    }
  }

  final MainActivity host;
  final Map<String, Brand> brands = new HashMap<>();
  final Map<String, Bitmap> images = new HashMap<>();
  final Map<String, List<WeakReference<View>>> loading = new HashMap<>();
  final Set<String> checkedLocal = new HashSet<>();
  final Map<String, Brand> byIata = new HashMap<>();
  final ExecutorService downloads = Executors.newSingleThreadExecutor();
  final android.content.SharedPreferences failures;
  Bitmap placeholder;


  AirlineLogos(MainActivity host) {
    this.host = host;
    failures = host.getSharedPreferences("logo-retry-v2", 0);
    try {
      JSONObject codes = new JSONObject(new String(host.readAsset("airline-codes.json"), java.nio.charset.StandardCharsets.UTF_8));
      Iterator<String> keys = codes.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        JSONArray row = codes.getJSONArray(key);
        add(key, row.getString(0), row.getString(1), 0xffc9dbd2);
      }
      placeholder = BitmapFactory.decodeStream(host.getAssets().open("logo-placeholder.png"));
    } catch (Exception ignored) { }

    add("CSS", "O3", "SF AIRLINES", 0xffffffff);
    add("QFA", "QF", "QANTAS", 0xffe21b2d);
    add("JST", "JQ", "JETSTAR", 0xffff6b00);
    add("VOZ", "VA", "VIRGIN AUSTRALIA", 0xffd71920);
    add("ANZ", "NZ", "AIR NEW ZEALAND", 0xffeeeeee);
    add("RXA", "ZL", "REX", 0xffef1b2d);
    add("QLK", "QF", "QANTASLINK", 0xffe21b2d);
    add("UAE", "EK", "EMIRATES", 0xffd71920);
    add("SIA", "SQ", "SINGAPORE", 0xfff2aa00);
    add("CPA", "CX", "CATHAY PACIFIC", 0xff007f79);
    add("QTR", "QR", "QATAR", 0xff7a1f4b);
    add("ETD", "EY", "ETIHAD", 0xffbd8b13);
    add("THA", "TG", "THAI", 0xff6f2c91);
    add("MAS", "MH", "MALAYSIA", 0xffd71920);
    add("GIA", "GA", "GARUDA", 0xff00a6b2);
    add("FJI", "FJ", "FIJI AIRWAYS", 0xffa29061);
    add("CAL", "CI", "CHINA AIRLINES", 0xffe85d8e);
    add("EVA", "BR", "EVA AIR", 0xff009b71);
    add("CSN", "CZ", "CHINA SOUTHERN", 0xff008acb);
    add("CES", "MU", "CHINA EASTERN", 0xffe31937);
    add("CCA", "CA", "AIR CHINA", 0xffd71920);
    add("JAL", "JL", "JAPAN AIRLINES", 0xffd71920);
    add("ANA", "NH", "ANA", 0xff1455a0);
    add("KAL", "KE", "KOREAN AIR", 0xff65a8d8);
    add("AAR", "OZ", "ASIANA", 0xff8b1c62);
    add("HVN", "VN", "VIETNAM AIRLINES", 0xff00a7b5);
    add("VJC", "VJ", "VIETJET", 0xffe21b2d);
    add("BAW", "BA", "BRITISH AIRWAYS", 0xff2d4f9d);
    add("DLH", "LH", "LUFTHANSA", 0xffffc400);
    add("KLM", "KL", "KLM", 0xff00a1de);
    add("UAL", "UA", "UNITED", 0xff1769aa);
    add("AAL", "AA", "AMERICAN", 0xff2b5797);
    add("DAL", "DL", "DELTA", 0xffc8102e);
    add("ACA", "AC", "AIR CANADA", 0xffd8292f);
  }

  void add(String icao, String iata, String name, int color) {
    Brand b = new Brand(iata, name, color);
    brands.put(icao, b);
    byIata.put(iata, b);
  }

  String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace(" ", "");
  }

  String icao(String call) {
    String c = normalize(call);
    return c.matches("[A-Z]{3}[0-9].*") ? c.substring(0, 3) : "";
  }

  Brand brand(String call) {
    String c = normalize(call);
    Brand b = brands.get(icao(c));
    if (b != null) return b;
    if (c.matches("[A-Z0-9]{2}[0-9].*")) return byIata.get(c.substring(0, 2));
    // The larger operator database also identifies operators without IATA codes.
    String key = icao(c);
    JSONObject operators = host.db == null ? null : host.db.optJSONObject("operators");
    JSONArray row = operators == null ? null : operators.optJSONArray(key);
    return row == null ? null : new Brand("", row.optString(0).trim(), 0xffc9dbd2);
  }

  String code(String call, String routeIata) {
    Brand b = brand(call);
    if (b != null && !b.iata.isEmpty()) return b.iata;
    String c = normalize(routeIata);
    return c.matches("[A-Z0-9]{2}") ? c : "";
  }

  String fallback(String call, String routeIata) {
    String c = code(call, routeIata);
    if (!c.isEmpty()) return c;
    return brand(call) != null ? icao(call) : "UNKNOWN";
  }

  boolean usable(Bitmap bitmap) {
    if (bitmap == null || bitmap.getWidth() < 2 || bitmap.getHeight() < 2) return false;
    if (placeholder != null && bitmap.sameAs(placeholder)) return false;
    for (int y = 0; y < bitmap.getHeight(); y++)
      for (int x = 0; x < bitmap.getWidth(); x++)
        if (Color.alpha(bitmap.getPixel(x, y)) > 40) return true;
    return false;
  }

  Bitmap image(String call, String routeIata, View listener) {
    String code = code(call, routeIata);
    if (code.isEmpty()) return null;
    // These providers currently serve artwork for previous holders of these codes.
    if (Arrays.asList("GI", "I9", "AZ").contains(code)) return null;
    Bitmap ready = images.get(code);
    if (ready != null) return ready;
    File dir = new File(host.getCacheDir(), "airline-logos");
    File file = new File(dir, code + ".png");
    if (checkedLocal.add(code)) {
      try (InputStream in = host.getAssets().open("airline-logos/" + code + ".png")) {
        ready = BitmapFactory.decodeStream(in);
      } catch (IOException ignored) { }
      if (!usable(ready) && file.exists()) ready = BitmapFactory.decodeFile(file.toString());
      if (usable(ready)) {
        images.put(code, ready);
        return ready;
      }
    }
    if (failures.getLong(code, 0) > System.currentTimeMillis()) return null;
    List<WeakReference<View>> listeners = loading.get(code);
    if (listeners != null) {
      for (WeakReference<View> ref : listeners) if (ref.get() == listener) return null;
      listeners.add(new WeakReference<>(listener));
      return null;
    }
    listeners = new ArrayList<>();
    listeners.add(new WeakReference<>(listener));
    loading.put(code, listeners);
    downloads.execute(() -> {
      Bitmap result = null;
      long retry = 15 * 60 * 1000L;
      for (String url : new String[] {
          "https://www.gstatic.com/flights/airline_logos/70px/" + Api.enc(code) + ".png",
          "https://pics.avs.io/200/200/" + Api.enc(code) + ".png"}) {
        try {
          byte[] data = Api.request(url, "", null, "");
          Bitmap candidate = BitmapFactory.decodeByteArray(data, 0, data.length);
          if (usable(candidate)) {
            result = candidate;
            dir.mkdirs();
            Files.write(file.toPath(), data);
            break;
          } else retry = 7 * 86400000L;
        } catch (Exception ignored) { }
      }
      Bitmap bitmap = result;
      long delay = retry;
      host.runOnUiThread(() -> {
        if (bitmap != null) {
          images.put(code, bitmap);
          failures.edit().remove(code).apply();
        } else failures.edit().putLong(code, System.currentTimeMillis() + delay).apply();
        List<WeakReference<View>> waiting = loading.remove(code);
        if (waiting != null) for (WeakReference<View> ref : waiting) {
          View view = ref.get();
          if (view != null && view.isAttachedToWindow()) view.invalidate();
        }
      });
    });
    return null;
  }
}
