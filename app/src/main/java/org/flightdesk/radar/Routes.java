package org.flightdesk.radar;

import java.util.*;
import java.util.function.Consumer;
import org.json.*;

/** Callsign routes are best effort, cached independently from fast position updates. */
final class Routes {
  final MainActivity a;
  final android.content.SharedPreferences cache;
  final LinkedHashMap<String, List<Consumer<String>>> queue = new LinkedHashMap<>();
  boolean busy;
  long next;

  Routes(MainActivity a) {
    this.a = a;
    cache = a.getSharedPreferences("routes", 0);
    if (!a.prefs.b("routeMetadataV2", false)) {
      cache.edit().clear().apply();
      a.prefs.put("routeMetadataV2", true);
    }
  }

  JSONObject cached(String call) {
    try {
      JSONObject j = new JSONObject(cache.getString(call, "{}"));
      return j.optLong("until") > System.currentTimeMillis() ? j : null;
    } catch (Exception e) {
      return null;
    }
  }

  String label(String call) {
    JSONObject j = cached(call);
    return j == null ? "UNKNOWN–UNKNOWN" : j.optString("label", "UNKNOWN–UNKNOWN");
  }

  String flightNumber(String call) {
    JSONObject j = cached(call);
    String value = j == null ? "" : j.optString("flightNumber", "");
    if (value.isEmpty() && a.logos != null) {
      AirlineLogos.Brand brand = a.logos.brand(call);
      if (brand != null && !brand.iata.isEmpty()) {
        String normalized = a.logos.normalize(call);
        value = a.logos.icao(normalized).isEmpty() ? normalized : brand.iata + normalized.substring(3);
      }
    }
    return value.isEmpty() ? "UNKNOWN" : value;
  }

  String airlineIata(String call) {
    JSONObject j = cached(call);
    String value = j == null ? "" : j.optString("airlineIata", "");
    if (value.isEmpty() && a.logos != null && a.logos.brand(call) != null)
      value = a.logos.brand(call).iata;
    return value;
  }

  String airlineName(String call) {
    JSONObject j = cached(call);
    return j == null ? "" : j.optString("airlineName", "");
  }

  void request(String call, Consumer<String> callback) {
    JSONObject j = cached(call);
    if (j != null) {
      if (callback != null) callback.accept(j.optString("detail"));
      return;
    }
    List<Consumer<String>> listeners = queue.computeIfAbsent(call, k -> new ArrayList<>());
    if (callback != null) listeners.add(callback);
    pump();
  }

  void prefetch(List<Aircraft> aircraft) {
    List<Aircraft> ordered = new ArrayList<>(aircraft);
    ordered.sort(Comparator.comparingInt(x -> x.ground ? 1 : 0));
    int n = 0;
    for (Aircraft x : ordered)
      if (x.call.matches("[A-Z]{2,3}[0-9][A-Z0-9]*") && n++ < 20) request(x.call, null);
    pump();
  }

  static String code(JSONObject airport) {
    String i = airport.optString("iata_code", "");
    if (i.matches("[A-Z]{3}")) return i;
    String icao = airport.optString("icao_code", "");
    return icao.matches("[A-Z0-9]{3,4}") ? icao : "—";
  }

  void pump() {
    if (busy || !a.active || queue.isEmpty() || System.currentTimeMillis() < next) return;
    String call = queue.keySet().iterator().next();
    busy = true;
    next = System.currentTimeMillis() + 2000;
    a.net.execute(
        () -> {
          String label = "UNKNOWN–UNKNOWN", detail = "航线资料不可用；呼号不一定对应定期航班。";
          String flightNumber = "", airlineIata = "", airlineName = "";
          boolean ok = false;
          long delay = 2000;
          try {
            JSONObject r =
                Api.json("https://api.adsbdb.com/v0/callsign/" + Api.enc(call), "", null)
                    .getJSONObject("response")
                    .getJSONObject("flightroute");
            JSONObject origin = r.getJSONObject("origin"),
                destination = r.getJSONObject("destination");
            JSONObject airline = r.optJSONObject("airline");
            label = code(origin) + "–" + code(destination);
            flightNumber = r.optString("callsign_iata", "");
            if (airline != null) {
              airlineIata = airline.optString("iata", "");
              airlineName = airline.optString("name", "");
            }
            detail =
                label + "\n" + origin.optString("name") + " → " + destination.optString("name");
            ok = true;
          } catch (Api.Failure e) {
            if (e.status == 429) delay = Math.max(120000, e.retry);
          } catch (Exception ignored) {
          }
          String text = detail, shortLabel = label;
          String finalFlightNumber = flightNumber,
              finalAirlineIata = airlineIata,
              finalAirlineName = airlineName;
          boolean success = ok;
          long cooldown = delay;
          a.runOnUiThread(
              () -> {
                busy = false;
                next = System.currentTimeMillis() + cooldown;
                try {
                  cache
                      .edit()
                      .putString(
                          call,
                          new JSONObject()
                              .put("label", shortLabel)
                              .put("detail", text)
                              .put("flightNumber", finalFlightNumber)
                              .put("airlineIata", finalAirlineIata)
                              .put("airlineName", finalAirlineName)
                              .put(
                                  "until",
                                  System.currentTimeMillis() + (success ? 21600000 : 1800000))
                              .toString())
                      .apply();
                } catch (Exception ignored) {
                }
                List<Consumer<String>> listeners = queue.remove(call);
                if (listeners != null) for (Consumer<String> c : listeners) c.accept(text);
                if (a.radar != null) a.radar.invalidate();
                if (a.page.equals("最近航班")) a.showPage();
              });
        });
  }
}
