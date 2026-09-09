package org.flightdesk.radar;

import android.Manifest;
import android.app.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.CancellationSignal;
import android.widget.*;
import java.util.*;

final class PositionPicker {
  final MainActivity host;
  CancellationSignal signal;

  PositionPicker(MainActivity h) {
    host = h;
  }

  void show() {
    LinearLayout col = host.column();
    EditText lat = host.field(col, "纬度（WGS84）", "" + host.prefs.lat(), false),
        lon = host.field(col, "经度（WGS84）", "" + host.prefs.lon(), false);
    col.addView(
        host.button(
            "使用经纬度",
            () -> {
              try {
                set(
                    Double.parseDouble(lat.getText().toString()),
                    Double.parseDouble(lon.getText().toString()));
              } catch (Exception e) {
                host.toast("经纬度无效");
              }
            }));
    col.addView(host.button("获取当前位置", this::current));
    EditText address = host.field(col, "地址、城市或机场名称", "", false);
    col.addView(host.button("搜索地址", () -> search(address.getText().toString().trim())));
    col.addView(
        host.text(
            "地址搜索：系统服务 / Photon · © OpenStreetMap contributors。仅点击搜索时发送输入地址；定位不在后台跟踪。",
            12,
            0xff8aa897));
    ScrollView scroll = new ScrollView(host);
    scroll.addView(col);
    new AlertDialog.Builder(host)
        .setTitle("雷达中心位置")
        .setView(scroll)
        .setNegativeButton("关闭", null)
        .show();
  }

  void set(double lat, double lon) {
    if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180)
      throw new IllegalArgumentException();
    host.prefs.put("lat", "" + lat);
    host.prefs.put("lon", "" + lon);
    host.resetArea();
    host.toast("已更新雷达中心位置");
  }

  void cancel() {
    if (signal != null) {
      signal.cancel();
      signal = null;
    }
  }

  void current() {
    if (host.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        != PackageManager.PERMISSION_GRANTED) {
      host.requestPermissions(
          new String[] {
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION
          },
          71);
      return;
    }
    LocationManager lm = host.getSystemService(LocationManager.class);
    String provider =
        lm.isProviderEnabled("fused")
            ? "fused"
            : lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                ? LocationManager.NETWORK_PROVIDER
                : LocationManager.GPS_PROVIDER;
    if (!lm.isProviderEnabled(provider)) {
      host.toast("请先打开手机的位置服务，或手动输入位置");
      return;
    }
    cancel();
    signal = new CancellationSignal();
    CancellationSignal activeSignal = signal;
    host.toast("正在定位，最长等待 25 秒…");
    try {
      lm.getCurrentLocation(
          provider,
          signal,
          host.getMainExecutor(),
          location -> {
            if (signal != activeSignal) return;
            signal = null;
            if (location == null) {
              host.toast("暂时无法定位，可到开阔处重试或手动输入");
              return;
            }
            set(location.getLatitude(), location.getLongitude());
            host.toast("位置已更新，精度约 " + Math.round(location.getAccuracy()) + " 米");
          });
      host.handler.postDelayed(
          () -> {
            if (signal == activeSignal) {
              cancel();
              host.toast("定位超时，请重试或输入地址 / 经纬度");
            }
          },
          25000);
    } catch (Exception e) {
      cancel();
      host.toast("定位不可用，请检查权限及系统位置服务");
    }
  }

  final Map<String, List<Address>> addressCache = new LinkedHashMap<>();
  long addressNext;

  List<Address> lookup(String query) throws Exception {
    if (addressCache.containsKey(query)) return addressCache.get(query);
    List<Address> found = null;
    if (Geocoder.isPresent())
      try {
        found = new Geocoder(host, Locale.getDefault()).getFromLocationName(query, 8);
      } catch (Exception ignored) {
      }
    if (found == null || found.isEmpty()) {
      if (System.currentTimeMillis() < addressNext) throw new java.io.IOException("地址查询冷却中");
      addressNext = System.currentTimeMillis() + 2000;
      try {
        org.json.JSONObject payload =
            Api.json("https://photon.komoot.io/api/?q=" + Api.enc(query) + "&limit=8", "", null);
        org.json.JSONArray features = payload.optJSONArray("features");
        found = new ArrayList<>();
        if (features != null)
          for (int i = 0; i < features.length(); i++) {
            org.json.JSONObject item = features.getJSONObject(i),
                props = item.getJSONObject("properties");
            org.json.JSONArray xy = item.getJSONObject("geometry").getJSONArray("coordinates");
            Address place = new Address(Locale.getDefault());
            place.setLongitude(xy.getDouble(0));
            place.setLatitude(xy.getDouble(1));
            List<String> parts = new ArrayList<>();
            for (String k :
                new String[] {"name", "housenumber", "street", "city", "state", "country"}) {
              String v = props.optString(k, "");
              if (!v.isEmpty() && !parts.contains(v)) parts.add(v);
            }
            place.setAddressLine(0, String.join(" · ", parts));
            found.add(place);
          }
      } catch (Api.Failure e) {
        addressNext = System.currentTimeMillis() + e.retry;
        throw e;
      }
    }
    if (found != null && !found.isEmpty()) {
      if (addressCache.size() >= 32) addressCache.remove(addressCache.keySet().iterator().next());
      addressCache.put(query, found);
    }
    return found;
  }

  boolean searching;

  void search(String query) {
    if (query.isEmpty()) {
      host.toast("请输入地址或城市名称");
      return;
    }
    if (searching) return;
    searching = true;
    host.toast("正在搜索地址…");
    host.net.execute(
        () -> {
          List<Address> found = null;
          try {
            found = lookup(query);
          } catch (Exception ignored) {
          }
          final List<Address> results = found;
          host.runOnUiThread(
              () -> {
                searching = false;
                if (host.isFinishing() || !host.active) return;
                if (results == null || results.isEmpty()) {
                  host.toast("未找到地址，请尝试城市英文名或经纬度");
                  return;
                }
                String[] labels = new String[results.size()];
                for (int i = 0; i < labels.length; i++) {
                  Address a = results.get(i);
                  labels[i] =
                      a.getMaxAddressLineIndex() >= 0 ? a.getAddressLine(0) : a.getFeatureName();
                }
                new AlertDialog.Builder(host)
                    .setTitle("选择雷达中心")
                    .setItems(
                        labels,
                        (d, i) -> {
                          Address a = results.get(i);
                          set(a.getLatitude(), a.getLongitude());
                        })
                    .setNegativeButton("取消", null)
                    .show();
              });
        });
  }
}
