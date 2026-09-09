package org.flightdesk.radar;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.LinearLayout;
import java.io.*;
import java.util.*;
import org.json.*;

/** Runs on the connected device. No external accounts or credentials are required. */
public class DeviceChecks extends Instrumentation {
  int passed = 0;
  StringBuilder log = new StringBuilder();

  void check(boolean ok, String label) {
    if (!ok) throw new AssertionError(label);
    passed++;
    Bundle update = new Bundle();
    update.putString("stream", "PASS " + label + "\n");
    sendStatus(0, update);
    log.append("PASS ").append(label).append('\n');
  }

  byte[] asset(String s) throws Exception {
    try (InputStream in = getContext().getAssets().open(s);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) > 0) out.write(b, 0, n);
      return out.toByteArray();
    }
  }

  void shot(String name) throws Exception {
    android.os.SystemClock.sleep(200);
    android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot();
    if (bitmap == null) throw new AssertionError("screenshot failed");
    try (FileOutputStream o =
        new FileOutputStream(new File(getTargetContext().getFilesDir(), name))) {
      bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o);
    }
  }

  void pinch(MainActivity a, float start, float end) throws Exception {
    int[] location = new int[2];
    location[0] = location[1] = 0;
    float cx = location[0] + a.radar.getWidth() / 2f, cy = location[1] + a.radar.getHeight() / 2f;
    long down = SystemClock.uptimeMillis();
    MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
    MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
    for (int i = 0; i < 2; i++) {
      properties[i] = new MotionEvent.PointerProperties();
      properties[i].id = i;
      properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
      coords[i] = new MotionEvent.PointerCoords();
      coords[i].y = cy;
      coords[i].pressure = 1;
      coords[i].size = 1;
    }
    for (int step = -2; step <= 14; step++) {
      float span = step < 0 ? start : start + (end - start) * Math.min(step, 12) / 12;
      coords[0].x = cx - span / 2;
      coords[1].x = cx + span / 2;
      int action =
          step == -2
              ? MotionEvent.ACTION_DOWN
              : step == -1
                  ? MotionEvent.ACTION_POINTER_DOWN | 256
                  : step == 13
                      ? MotionEvent.ACTION_POINTER_UP | 256
                      : step == 14 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
      int count = step == -2 || step == 14 ? 1 : 2;
      MotionEvent event =
          MotionEvent.obtain(
              down,
              SystemClock.uptimeMillis(),
              action,
              count,
              properties,
              coords,
              0,
              0,
              1,
              1,
              0,
              0,
              InputDevice.SOURCE_TOUCHSCREEN,
              0);
      runOnMainSync(() -> a.radar.dispatchTouchEvent(event));
      event.recycle();
      SystemClock.sleep(20);
    }
    SystemClock.sleep(100);
  }

  public void onCreate(Bundle b) {
    super.onCreate(b);
    start();
  }

  public void onStart() {
    Bundle out = new Bundle();
    MainActivity a = null;
    try {
      long now = System.currentTimeMillis();
      check(Api.limitedDelay(1, 0) == 60000 && Api.limitedDelay(2, 0) == 120000
          && Api.limitedDelay(3, 0) == 240000 && Api.limitedDelay(20, 0) == 900000,
          "429 backoff is separate from network retry");
      check(Api.limitedDelay(1, 1800000) == 1800000,
          "server retry time is never shortened by local cap");
      check(Api.retryDelay(1) == 15000 && Api.retryDelay(2) == 30000
          && Api.retryDelay(3) == 60000 && Api.retryDelay(100) == 60000,
          "network retry starts at 15 seconds and caps at 60 seconds");
      JSONObject metarFixture =
          new JSONArray(new String(asset("metar-live.json"))).getJSONObject(0);
      String decoded = Metar.decode(metarFixture);
      check(
          decoded.contains("至少 10 km")
              && decoded.contains("1025 hPa")
              && decoded.contains("1800 ft"),
          "METAR visibility pressure cloud units");
      check(
          Metar.weather("-TSRA BR").contains("小雷暴雨") && Metar.weather("-TSRA BR").contains("轻雾"),
          "METAR weather Chinese decoding");
      check(
          Metar.compact(metarFixture).contains("中文")
              && Metar.compact(metarFixture).contains("至少10KM"),
          "compact METAR includes Chinese translation");
      check(Metar.decode(new JSONObject()).contains("未知"), "missing METAR fields stay unknown");
      List<Aircraft> live =
          Aircraft.parse(new JSONObject(new String(asset("live-adsblol.json"))), "adsb.lol", now);
      check(live.size() > 0, "parse real adsb.lol response");
      Aircraft direction =
          Aircraft.parse(
                  new JSONObject(
                      "{\"ac\":[{\"hex\":\"123abc\",\"lat\":0,\"lon\":0,\"track\":270}]}"),
                  "adsb.lol",
                  now)
              .get(0);
      check(direction.heading == 270, "westbound track preserved for rotated aircraft");
      direction =
          Aircraft.parse(
                  new JSONObject("{\"ac\":[{\"hex\":\"123abc\",\"lat\":0,\"lon\":0}]}"),
                  "adsb.lol",
                  now)
              .get(0);
      check(Double.isNaN(direction.heading), "unknown direction remains unknown");
      check(Math.abs(Aircraft.distance(0, 179.9, 0, -179.9) - 22.24) < .1, "dateline distance");
      check(Aircraft.distance(0, 0, 0, 0) == 0, "zero distance");
      JSONObject sky =
          new JSONObject(
              "{\"states\":[[\"abc123\",\"TEST "
                  + " \",\"Australia\",0,1700000000,144.9,-37.8,1000,false,100,90,2,null,1100,\"7700\"]]}");
      Aircraft x = Aircraft.parse(sky, "OpenSky", now).get(0);
      check(
          Math.abs(x.speed - 194.3844) < .01 && x.emergency() && x.call.equals("TEST"),
          "OpenSky units and emergency");
      check(
          Aircraft.parse(new JSONObject("{\"states\":null}"), "OpenSky", now).isEmpty(),
          "empty OpenSky is valid");
      boolean rejected = false;
      try {
        Aircraft.parse(new JSONObject("{\"error\":\"denied\"}"), "adsb.lol", now);
      } catch (Exception e) {
        rejected = true;
      }
      check(rejected, "API rejection not treated as empty success");
      Aircraft y = new Aircraft();
      x.lat = y.lat = 0;
      x.lon = 0;
      y.lon = .01;
      x.alt = 1000;
      y.alt = 1100;
      x.seen = y.seen = now;
      List<Aircraft> pair = Arrays.asList(x, y);
      Aircraft.conflicts(pair, now);
      check(x.conflict && y.conflict, "conflict threshold");
      y.seen = now - 61000;
      Aircraft.conflicts(pair, now);
      check(!x.conflict, "stale contact excluded from conflict");
      byte[] tile = asset("map.bin");
      MapData m = MapData.parse(tile);
      check(m.lines.size() > 0 && m.marks.size() > 0, "real upstream map tile decode");
      tile[tile.length - 1] ^= 1;
      rejected = false;
      try {
        MapData.parse(tile);
      } catch (Exception e) {
        rejected = true;
      }
      check(rejected, "map CRC rejects corruption");
      Calendar c = Calendar.getInstance();
      c.set(2026, 8, 7, 8, 0, 0);
      c.set(Calendar.MILLISECOND, 0);
      long next = AlarmReceiver.next(7, 30, 1, c.getTimeInMillis());
      c.setTimeInMillis(next);
      check(
          c.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY && c.get(Calendar.DAY_OF_MONTH) == 14,
          "weekly alarm rolls to next selected weekday");
      check(AlarmReceiver.next(7, 0, 0, now) == -1, "empty weekdays do not schedule");
      Prefs p = new Prefs(getTargetContext());
      p.secret("testSecret", "local-test-value");
      check(
          p.secret("testSecret").equals("local-test-value")
              && !p.s("testSecret", "").contains("local-test-value"),
          "Keystore encryption roundtrip");
      p.p.edit().remove("testSecret").commit();
      ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
      getUiAutomation()
          .executeShellCommand("am start -n org.flightdesk.radar/.MainActivity")
          .close();
      a = (MainActivity) monitor.waitForActivityWithTimeout(10000);
      if (a == null) throw new AssertionError("Activity did not start");
      for (int attempt = 0; attempt < 10 && (a.isDestroyed() || !a.active); attempt++) {
        waitForIdleSync();
        MainActivity replacement =
            (MainActivity) monitor.waitForActivityWithTimeout(attempt == 0 ? 1500 : 300);
        if (replacement != null) a = replacement;
      }
      MainActivity screen = a;
      boolean keep = p.b("keep", false), protect = p.b("protect", true);
      int rest = p.i("restMinutes", 30);
      runOnMainSync(
          () -> {
            try {
              screen.active = true;
              screen.prefs.put("keep", true);
              screen.applyDisplay();
              check(
                  (screen.getWindow().getAttributes().flags
                          & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                      != 0,
                  "keep screen flag enabled");
              screen.prefs.put("keep", false);
              screen.applyDisplay();
              check(
                  (screen.getWindow().getAttributes().flags
                          & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                      == 0,
                  "keep screen flag cleared");
              screen.prefs.put("protect", true);
              screen.lastTouch = System.currentTimeMillis() - 61000;
              screen.applyDisplay();
              check(
                  screen.getWindow().getAttributes().screenBrightness <= .12f,
                  "idle brightness dim");
              screen.prefs.put("keep", true);
              screen.prefs.put("restMinutes", 5);
              screen.started = System.currentTimeMillis() - 300001;
              screen.applyDisplay();
              check(screen.rest.getVisibility() == View.VISIBLE, "scheduled black screen");
              screen.rest.performClick();
              check(screen.rest.getVisibility() == View.GONE, "touch wakes black screen");
              screen.prefs.put("protect", false);
              screen.applyDisplay();
              check(
                  screen.root.getTranslationX() == 0 && screen.root.getTranslationY() == 0,
                  "disable pixel shift");
              screen.prefs.put("keep", keep);
              screen.prefs.put("protect", protect);
              screen.prefs.put("restMinutes", rest);
              screen.applyDisplay();
            } catch (Throwable e) {
              getTargetContext().stopService(new Intent(getTargetContext(), AlarmService.class));
              throw new RuntimeException(e);
            }
          });

      runOnMainSync(
          () -> {
            check(
                screen.metar.nearest("YMML").get(0).optString("iata").equals("MEL"),
                "airport ICAO search");
            check(
                screen.metar.nearest("MEL").stream()
                    .anyMatch(v -> v.optString("id").equals("YMML")),
                "airport IATA search");
            check(!screen.metar.nearest("").isEmpty(), "nearest reporting airport available");
          });
      runOnMainSync(() -> screen.settings());
      android.os.SystemClock.sleep(350);
      runOnMainSync(
          () ->
              check(
                  screen.settingsDialog != null && screen.settingsDialog.isShowing(),
                  "settings opens on device"));
      shot("settings.png");
      runOnMainSync(() -> screen.settingsDialog.dismiss());
      runOnMainSync(
          () -> {
            screen.page = "天气时钟";
            screen.metar.station = "YMML";
            screen.metar.report = metarFixture;
            screen.metar.next = System.currentTimeMillis() + 300000;
            screen.showPage();
            check(screen.bigClock != null, "weather clock page renders");
          });
      shot("weather.png");
      runOnMainSync(
          () -> {
            screen.page = "闹钟";
            screen.showPage();
          });
      shot("alarms.png");
      runOnMainSync(
          () -> {
            screen.page = "雷达";
            screen.showPage();
            screen.setRequestedOrientation(
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
          });
      android.os.SystemClock.sleep(600);
      runOnMainSync(
          () -> check(screen.root.getHeight() > screen.root.getWidth(), "portrait layout"));
      shot("portrait.png");
      runOnMainSync(
          () ->
              screen.setRequestedOrientation(
                  android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE));
      android.os.SystemClock.sleep(600);
      runOnMainSync(
          () -> check(screen.root.getWidth() > screen.root.getHeight(), "landscape layout"));
      shot("landscape.png");
      List<Aircraft> savedAircraft = screen.aircraft;
      runOnMainSync(
          () -> {
            List<Aircraft> samples = new ArrayList<>();
            String[] calls = {"CSS123", "FDX456", "CHH789", "B-1234", "QFA7356"};
            check("O3".equals(screen.logos.code(" css123 ", "")), "normalized SF callsign identity");
            check("FX".equals(screen.logos.code("FDX123", "")), "cargo ICAO identity");
            check("HU".equals(screen.logos.code("CHH123", "")), "expanded airline code table");
            check("CZ".equals(screen.logos.code("CZ123", "")), "IATA flight number identity");
            check("QF".equals(screen.logos.fallback("", "QF")), "metadata-only identity fallback");
            check("UNKNOWN".equals(screen.logos.fallback("B-1234", "")), "registration is not an airline");
            check(!screen.logos.usable(screen.logos.placeholder), "generic tail is rejected");
            check(screen.logos.image("CSS123", "", screen.body) != null
                && screen.logos.image("CHH123", "", screen.body) != null
                && screen.logos.image("FDX123", "", screen.body) != null,
                "passenger and cargo logos available offline");
            for (int i = 0; i < calls.length; i++) {
              Aircraft sample = new Aircraft();
              sample.call = calls[i];
              sample.hex = "sample" + i;
              sample.lat = screen.prefs.lat() + .01 * (i + 1);
              sample.lon = screen.prefs.lon();
              sample.alt = 1200 + i * 500;
              sample.speed = 180 + i * 20;
              sample.heading = 45 + i * 25;
              sample.seen = System.currentTimeMillis();
              samples.add(sample);
            }
            screen.aircraft = samples;
            screen.prefs.put("nearestCount", 4);
            screen.page = "最近航班";
            screen.showPage();
            LinearLayout nearest = (LinearLayout) screen.body.getChildAt(0);
            check(nearest.getChildCount() == (screen.layoutProfile.scrollCards ? 2 : 5), "nearest page fits header and four flights");
            check(
                screen.logos.brand("QFA7356") != null
                    && screen.logos.brand("QFA7356").color == 0xffe21b2d,
                "local airline brand color library");
            check(
                screen.routes.flightNumber("QFA7356").startsWith("QF"),
                "local IATA flight number fallback");
          });
      shot("nearest.png");
      android.os.SystemClock.sleep(2500);
      shot("nearest-loaded.png");
      runOnMainSync(
          () -> {
            screen.prefs.put("nearestCount", 1);
            screen.showPage();
            LinearLayout nearest = (LinearLayout) screen.body.getChildAt(0);
            check(nearest.getChildCount() == 2, "nearest page can focus one flight");
          });
      shot("nearest-single.png");
      runOnMainSync(
          () -> {
            screen.prefs.put("nearestCount", 1);
            screen.aircraft = savedAircraft;
            screen.page = "雷达";
            screen.showPage();
          });
      RadarView original = screen.radar;
      double savedRange = screen.prefs.range();
      boolean savedStreets = screen.prefs.b("streets", true),
          savedEcho = screen.prefs.b("echo", false);
      runOnMainSync(
          () -> {
            screen.prefs.put("streets", false);
            screen.prefs.put("echo", false);
            for (String provider : new String[] {"adsb.lol", "OpenSky", "airplanes.live"})
              screen.api.blocked.put(provider, System.currentTimeMillis() + 120000);
          });
      runOnMainSync(() -> screen.commitRange(40));
      pinch(screen, 300, 1000);
      double zoomedIn = screen.prefs.range();
      Bundle diagnostic = new Bundle();
      diagnostic.putString(
          "stream",
          "Zoom result="
              + zoomedIn
              + " minSpan="
              + ViewConfiguration.get(screen).getScaledMinimumScalingSpan()
              + "\n");
      sendStatus(0, diagnostic);
      check(zoomedIn < 39, "two-finger spread decreases range");
      pinch(screen, 1000, 300);
      check(screen.prefs.range() > zoomedIn, "two-finger pinch increases range");
      for (int n = 0; n < 4; n++) {
        pinch(screen, 300, 1000);
        pinch(screen, 1000, 300);
      }
      runOnMainSync(
          () -> {
            check(screen.radar == original, "ten two-finger gestures keep the same radar view");
            check(
                Double.isFinite(screen.prefs.range())
                    && screen.prefs.range() >= 10
                    && screen.prefs.range() <= 460,
                "pinch range remains valid");
            screen.api.blocked.clear();
            screen.prefs.put("streets", savedStreets);
            screen.prefs.put("echo", savedEcho);
            screen.commitRange(savedRange);
          });

      runOnMainSync(
          () -> {
            check(
                screen.radar.getHeight() > screen.root.getHeight() * .88,
                "landscape radar uses full height");
            check(screen.radar.r > screen.radar.getHeight() * .43, "large radar circle");
            Aircraft touch = new Aircraft();
            touch.hex = "test-hit";
            screen.aircraft = new java.util.ArrayList<>(java.util.List.of(touch));
            screen.radar.labelHits.clear();
            screen.radar.hits.clear();
            screen.radar.labelHits.put(touch.hex, new android.graphics.RectF(10, 10, 300, 120));
            check(screen.radar.targets(200, 50).size() == 1, "entire flight label is tappable");
            screen.radar.labelHits.clear();
            screen.radar.hits.put(touch.hex, new android.graphics.PointF(100, 100));
            check(
                screen.radar.targets(100 + screen.dp(28), 100).size() == 1,
                "expanded aircraft touch target");
            screen.radar.selected = touch.hex;
            long tapTime = SystemClock.uptimeMillis();
            MotionEvent downTap =
                MotionEvent.obtain(tapTime, tapTime, MotionEvent.ACTION_DOWN, 100, 100, 0);
            screen.radar.onTouchEvent(downTap);
            downTap.recycle();
            MotionEvent tap =
                MotionEvent.obtain(tapTime, tapTime, MotionEvent.ACTION_UP, 100, 100, 0);
            screen.radar.onTouchEvent(tap);
            tap.recycle();
            check(screen.radar.selected.isEmpty(), "tap selected aircraft clears highlight");
            screen.radar.selected = touch.hex;
            tap = MotionEvent.obtain(tapTime, tapTime, MotionEvent.ACTION_UP, 1000, 1000, 0);
            screen.radar.onTouchEvent(tap);
            tap.recycle();
            check(screen.radar.selected.isEmpty(), "tap blank area clears highlight");
            screen.aircraft = new java.util.ArrayList<>();
          });

      runOnMainSync(
          () -> {
            screen.prefs.put("keep", true);
            screen.applyDisplay();
          });
      getUiAutomation().executeShellCommand("input keyevent KEYCODE_HOME").close();
      android.os.SystemClock.sleep(600);
      runOnMainSync(
          () -> {
            check(!screen.active, "background pauses refresh loop");
            check(
                (screen.getWindow().getAttributes().flags
                        & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    == 0,
                "background clears keep screen flag");
            screen.prefs.put("keep", keep);
          });

      AlarmManager am = getTargetContext().getSystemService(AlarmManager.class);
      check(
          android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms(),
          "exact alarm permission available");
      PendingIntent alarm =
          PendingIntent.getBroadcast(
              getTargetContext(),
              99,
              new Intent(getTargetContext(), AlarmReceiver.class)
                  .setAction("org.flightdesk.ALARM")
                  .putExtra("slot", 0),
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      am.setAlarmClock(
          new AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 6000, null), alarm);
      for (int n = 0; n < 60 && !AlarmService.playing; n++) android.os.SystemClock.sleep(200);
      check(AlarmService.playing, "scheduled alarm plays while app backgrounded");
      getTargetContext().stopService(new Intent(getTargetContext(), AlarmService.class));
      android.os.SystemClock.sleep(300);
      check(!AlarmService.playing, "alarm stops and releases player");
      out.putString("stream", "\n" + log + "All " + passed + " checks passed\n");
      finish(Activity.RESULT_OK, out);
    } catch (Throwable e) {
      getTargetContext().stopService(new Intent(getTargetContext(), AlarmService.class));
      out.putString("stream", "\n" + log + "FAIL " + e + "\n");
      finish(Activity.RESULT_CANCELED, out);
    }
  }
}
