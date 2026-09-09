package org.flightdesk.radar;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public class MainActivity extends Activity {
  Prefs prefs;
  LayoutProfile layoutProfile;
  final Api api = new Api();
  final Handler handler = new Handler(Looper.getMainLooper());
  final ExecutorService net = Executors.newFixedThreadPool(3);
  List<Aircraft> aircraft = new ArrayList<>();
  MapData map;
  Bitmap echo;
  double echoKm;
  JSONObject db;
  byte[] silhouettes;
  RadarView radar;
  LinearLayout root, body, side;
  FrameLayout frame;
  TextView status, clock, bigClock, weather, selection;
  View rest;
  boolean active = false, fetching = false;
  long lastFetch = 0, lastWeather = 0, started = 0, lastTouch = 0, nextFetch = 0;
  int generation = 0, failures = 0;
  String source = "—", error = "", mapKey = "", weatherInfo = "天气等待更新";
  String page = "雷达";
  AlertDialog settingsDialog;
  Routes routes;
  StreetMap streets;
  Metar metar;
  PositionPicker positions;
  AirlineLogos logos;
  TextView metarText;

  int dp(float v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }

  void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }

  public void onCreate(Bundle b) {
    super.onCreate(b);
    prefs = new Prefs(this);
    if (!prefs.b("fastRefreshV3", false)) {
      if (prefs.i("poll", 30) == 30) prefs.put("poll", 10);
      prefs.put("fastRefreshV3", true);
    }
    if (!prefs.b("refresh15V6", false)) {
      if (prefs.i("poll", 10) < 15) prefs.put("poll", 15);
      prefs.put("refresh15V6", true);
    }
    if (!prefs.b("nearestSingleV7", false)) {
      prefs.put("nearestCount", 1);
      prefs.put("nearestSingleV7", true);
    }
    routes = new Routes(this);
    if (!prefs.b("stableRefresh30", false)) {
      if (prefs.i("poll", 15) < 30) prefs.put("poll", 30);
      prefs.put("stableRefresh30", true);
    }
    streets = new StreetMap(this);
    metar = new Metar(this);
    positions = new PositionPicker(this);
    logos = new AirlineLogos(this);
    setRequestedOrientation(
        prefs.b("landscape", true)
            ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            : ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
    getWindow().setStatusBarColor(Color.BLACK);
    getWindow().setNavigationBarColor(Color.BLACK);
    getWindow().getDecorView();
    if (Build.VERSION.SDK_INT >= 30) {
      getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
      getWindow()
          .getInsetsController()
          .setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }
    try {
      db = new JSONObject(new String(readAsset("aircraft.json"), StandardCharsets.UTF_8));
      silhouettes = readAsset("silhouettes.a8");
    } catch (Exception e) {
      toast("机型资料读取失败");
    }
    build();
    AlarmReceiver.schedule(this);
  }

  byte[] readAsset(String name) throws Exception {
    try (InputStream i = getAssets().open(name);
        ByteArrayOutputStream o = new ByteArrayOutputStream()) {
      byte[] b = new byte[8192];
      int n;
      while ((n = i.read(b)) > 0) o.write(b, 0, n);
      return o.toByteArray();
    }
  }

  TextView text(String t, int size, int color) {
    TextView v = new DotMatrixTextView(this);
    v.setText(t);
    v.setTextSize((size + 2) * (getResources().getConfiguration().smallestScreenWidthDp >= 600 ? 1.25f : 1f));
    v.setTextColor(color);
    v.setPadding(dp(10), dp(6), dp(10), dp(6));
    return v;
  }

  Button button(String t, Runnable r) {
    Button b = new DotMatrixButton(this);
    b.setText(t);
    b.setTextSize(15);
    b.setAutoSizeTextTypeUniformWithConfiguration(12, 15, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
    b.setAllCaps(false);
    b.setTextColor(0xff8fe6c1);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    android.graphics.drawable.GradientDrawable bg =
        new android.graphics.drawable.GradientDrawable();
    bg.setColor(0xff0d1c17);
    bg.setCornerRadius(dp(8));
    bg.setStroke(dp(1), 0xff254436);
    b.setBackground(
        new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0xff285640), bg, null));
    b.setPadding(dp(8), dp(3), dp(8), dp(3));
    b.setOnClickListener(v -> r.run());
    return b;
  }

  LinearLayout column() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  void board(View view, int stroke) {
    android.graphics.drawable.GradientDrawable bg =
        new android.graphics.drawable.GradientDrawable();
    bg.setColor(0xff030a08);
    bg.setCornerRadius(dp(7));
    bg.setStroke(dp(1), stroke);
    view.setBackground(bg);
  }

  void build() {
    layoutProfile = new LayoutProfile(getResources().getConfiguration());
    frame = new FrameLayout(this);
    frame.setBackgroundColor(Color.BLACK);
    root = column();
    root.setPadding(dp(12), dp(5), dp(12), dp(5));
    frame.addView(root, new FrameLayout.LayoutParams(-1, -1));
    setContentView(frame);
    frame.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.displayCutout() | WindowInsets.Type.systemBars());
          frame.setPadding(safe.left, safe.top, safe.right, safe.bottom);
          return insets;
        });
    root.setBackgroundColor(0xff040a09);
    boolean landscape = layoutProfile.rail;
    body = new LinearLayout(this);
    status = text("连接数据源…", 10, 0xff739b88);
    status.setSingleLine(true);
    status.setEllipsize(android.text.TextUtils.TruncateAt.END);
    clock = text(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()), 16, 0xffc7ffe8);
    clock.setMaxLines(1);
    clock.setGravity(Gravity.CENTER);
    clock.setPadding(dp(4), dp(4), dp(4), dp(4));
    if (landscape) {
      LinearLayout content = new LinearLayout(this);
      root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
      root.addView(status, new LinearLayout.LayoutParams(-1, dp(28)));
      LinearLayout main = column();
      main.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
      content.addView(main, new LinearLayout.LayoutParams(0, -1, 1));
      LinearLayout panel = column();
      TextView title = text("FLIGHT RADAR", 10, 0xffa5f0cf);
      title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
      title.setGravity(Gravity.CENTER);
      clock.setGravity(Gravity.CENTER);
      title.setMaxLines(1);
      panel.addView(title, new LinearLayout.LayoutParams(-1, dp(32)));
      panel.addView(clock, new LinearLayout.LayoutParams(-1, dp(42)));
      String[] labels = {"雷达", "最近航班", "天气 / METAR", "闹钟", "更多工具", "设置"};
      Runnable[] actions = {
        () -> openPage("雷达"),
        () -> openPage("最近航班"),
        () -> openPage("天气时钟"),
        () -> openPage("闹钟"),
        this::toolsMenu,
        this::settings
      };
      ScrollView navigation = new ScrollView(this);
      LinearLayout navItems = column();
      navigation.setFillViewport(true);
      navigation.addView(navItems);
      for (int k = 0; k < labels.length; k++) {
        navItems.addView(button(labels[k], actions[k]), new LinearLayout.LayoutParams(-1, dp(48)));
      }
      panel.addView(navigation, new LinearLayout.LayoutParams(-1, 0, 1));
      LinearLayout.LayoutParams rail = new LinearLayout.LayoutParams(dp(layoutProfile.railWidth), -1);
      rail.leftMargin = dp(6);
      content.addView(panel, rail);
    } else {
      LinearLayout top = new LinearLayout(this);
      top.setBaselineAligned(false);
      TextView title = text(layoutProfile.width < 360 ? "RADAR" : "FLIGHT RADAR", 15, 0xffa5f0cf);
      top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
      clock.setAutoSizeTextTypeUniformWithConfiguration(12, 18, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
      top.addView(clock, new LinearLayout.LayoutParams(dp(110), dp(48)));
      top.addView(button("设置", this::settings));
      root.addView(top);
      LinearLayout tabs = new LinearLayout(this);
      tabs.setBaselineAligned(false);
      for (String t : new String[] {"雷达", "最近航班", "天气", "闹钟"})
        tabs.addView(
            button(
                t,
                () -> {
                  page = t.startsWith("天气") ? "天气时钟" : t;
                  showPage();
                }),
            new LinearLayout.LayoutParams(0, dp(48), 1));
      root.addView(tabs);
      root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
      LinearLayout controls = new LinearLayout(this);
      controls.addView(
          button("更多工具", this::toolsMenu), new LinearLayout.LayoutParams(0, dp(48), 1));
      root.addView(controls);
      root.addView(status);
    }
    showPage();
    rest = new View(this);
    rest.setBackgroundColor(Color.BLACK);
    rest.setVisibility(View.GONE);
    rest.setOnClickListener(
        v -> {
          lastTouch = System.currentTimeMillis();
          started = lastTouch;
          rest.setVisibility(View.GONE);
        });
    frame.addView(rest, new FrameLayout.LayoutParams(-1, -1));
    applyDisplay();
  }

  void showPage() {
    if (body == null) return;
    body.removeAllViews();
    radar = null;
    side = null;
    selection = null;
    weather = null;
    bigClock = null;
    body.setOrientation(
        getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
            ? LinearLayout.HORIZONTAL
            : LinearLayout.VERTICAL);
    if (page.equals("雷达")) {
      radar = new RadarView(this);
      radar.running = active;
      body.addView(
          radar,
          new LinearLayout.LayoutParams(
              body.getOrientation() == LinearLayout.HORIZONTAL ? 0 : -1,
              body.getOrientation() == LinearLayout.HORIZONTAL ? -1 : 0,
              1));
    } else if (page.equals("最近航班")) {
      nearestFlightsPage();
    } else if (page.equals("天气时钟")) {
      LinearLayout layout = new LinearLayout(this);
      boolean wide = layoutProfile.wideWeather;
      layout.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
      body.addView(layout, new LinearLayout.LayoutParams(-1, -1));
      LinearLayout col = column(), aviation = column();
      col.setPadding(dp(8), dp(6), dp(8), dp(6));
      aviation.setPadding(dp(8), dp(6), dp(8), dp(6));
      board(col, 0xff275844);
      board(aviation, 0xff275844);
      ScrollView overviewScroll = new ScrollView(this), aviationScroll = new ScrollView(this);
      overviewScroll.setFillViewport(true);
      aviationScroll.setFillViewport(true);
      overviewScroll.addView(col);
      aviationScroll.addView(aviation);
      layout.addView(
          overviewScroll,
          wide
              ? new LinearLayout.LayoutParams(0, -1, .38f)
              : new LinearLayout.LayoutParams(-1, 0, .38f));
      layout.addView(
          aviationScroll,
          wide
              ? new LinearLayout.LayoutParams(0, -1, .62f)
              : new LinearLayout.LayoutParams(-1, 0, .62f));
      col.addView(dot("LOCAL WEATHER  /  本地天气", 11, 0xff67e1b2));
      bigClock =
          text(
              new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()),
              28,
              0xff95c5ae);
      bigClock.setTypeface(Typeface.MONOSPACE);
      bigClock.setMaxLines(1);
      bigClock.setAutoSizeTextTypeUniformWithConfiguration(18, 32, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
      col.addView(bigClock, new LinearLayout.LayoutParams(-1, dp(62)));
      weather = dot(weatherInfo, 15, 0xff9deac3);
      col.addView(weather, new LinearLayout.LayoutParams(-1, -2));
      col.addView(dot("OPEN-METEO  /  10 MIN AUTO", 9, 0xff658b78));
      LinearLayout wxActions = new LinearLayout(this);
      wxActions.addView(
          button(
              "立即刷新",
              () -> {
                lastWeather = 0;
                refreshWeather();
              }),
          new LinearLayout.LayoutParams(0, dp(43), 1));
      wxActions.addView(
          button("中心位置", () -> positions.show()), new LinearLayout.LayoutParams(0, dp(43), 1));
      col.addView(wxActions);
      aviation.addView(dot("AVIATION WEATHER  /  航空气象", 11, 0xff67e1b2));
      aviation.addView(
          button("选择 METAR 机场 · 最近 / 搜索", this::chooseMetar),
          new LinearLayout.LayoutParams(-1, dp(43)));
      metarText = dot(metar.display(), 15, 0xffe1fff0);
      metarText.setTextIsSelectable(true);
      aviation.addView(metarText, new LinearLayout.LayoutParams(-1, -2));
      metar.refresh();
    } else if (page.equals("闹钟")) alarmPage();
  }

  void openPage(String destination) {
    page = destination;
    showPage();
  }

  void toolsMenu() {
    LinearLayout col = column();
    col.setPadding(dp(8), dp(8), dp(8), dp(8));
    TextView heading = dot("MORE TOOLS  /  更多工具", 13, 0xff8fe6c1);
    col.addView(heading, new LinearLayout.LayoutParams(-1, dp(44)));
    AlertDialog[] holder = new AlertDialog[1];
    String[] labels = {"中心位置", "雷达图层", "范围 / 缩放", "保存截图", "许可证与数据来源"};
    Runnable[] actions = {
      positions::show, this::layers, this::rangePicker, this::screenshot, this::about
    };
    for (int k = 0; k < labels.length; k++) {
      Runnable action = actions[k];
      col.addView(
          button(
              labels[k],
              () -> {
                if (holder[0] != null) holder[0].dismiss();
                action.run();
              }),
          new LinearLayout.LayoutParams(-1, dp(52)));
    }
    AlertDialog dialog = new AlertDialog.Builder(this).setView(col).create();
    holder[0] = dialog;
    dialog.show();
    dialog
        .getWindow()
        .setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xff030807));
    dialog.getWindow().setLayout(dp(Math.min(420, layoutProfile.width - 32)), WindowManager.LayoutParams.WRAP_CONTENT);
  }

  String operatorName(Aircraft a) {
    String code = a.call.substring(0, Math.min(3, a.call.length()));
    AirlineLogos.Brand brand = logos.brand(a.call);
    String routed = routes.airlineName(a.call);
    if (!routed.isEmpty()) return routed.toUpperCase(Locale.ROOT);
    if (brand != null) return brand.name;
    try {
      JSONArray value = db.optJSONObject("operators").optJSONArray(code);
      if (value != null && !value.optString(0).trim().isEmpty()) return value.optString(0).trim();
    } catch (Exception ignored) {
    }
    return "UNKNOWN AIRLINE";
  }

  TextView dot(String value, int size, int color) {
    TextView view = text(value, size, color);
    view.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
    view.setGravity(Gravity.CENTER_VERTICAL);
    return view;
  }

  void nearestFlightsPage() {
    LinearLayout screen = column();
    screen.setBackgroundColor(Color.BLACK);
    body.addView(screen, new LinearLayout.LayoutParams(-1, -1));
    boolean single = prefs.i("nearestCount", 1) == 1;
    LinearLayout heading = new LinearLayout(this);
    TextView headingText = dot("NEAREST TRAFFIC", 11, 0xff67e1b2);
    headingText.setSingleLine(true);
    headingText.setEllipsize(android.text.TextUtils.TruncateAt.END);
    heading.addView(headingText, new LinearLayout.LayoutParams(0, -1, 1));
    heading.addView(
        button(
            single ? "SHOW MULTIPLE" : "SHOW ONE",
            () -> {
              prefs.put("nearestCount", single ? 4 : 1);
              showPage();
            }),
        new LinearLayout.LayoutParams(dp(116), -1));
    screen.addView(heading, new LinearLayout.LayoutParams(-1, dp(42)));
    List<Aircraft> ordered = new ArrayList<>(aircraft);
    if (!prefs.b("ground", true)) ordered.removeIf(a -> a.ground);
    ordered.sort(
        Comparator.comparingDouble(a -> Aircraft.distance(prefs.lat(), prefs.lon(), a.lat, a.lon)));
    int limit = Math.min(single ? 1 : 4, ordered.size());
    if (limit == 0) {
      screen.addView(
          dot("NO TRAFFIC IN RANGE", 18, 0xff6f8e80),
          new LinearLayout.LayoutParams(-1, 0, 1));
      return;
    }
    LinearLayout cards = screen;
    if (layoutProfile.scrollCards) {
      ScrollView scroller = new ScrollView(this);
      cards = column();
      scroller.setFillViewport(true);
      scroller.addView(cards);
      screen.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
    }
    for (int k = 0; k < limit; k++) {
      Aircraft a = ordered.get(k);
      routes.request(a.call, ignored -> {});
      String flight = routes.flightNumber(a.call);
      String route = routes.label(a.call).replace("—", "UNKNOWN");
      String iata = routes.airlineIata(a.call);
      if (single) {
        LinearLayout card = column();
        board(card, 0xff5be6af);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        if (layoutProfile.compactCards) top.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams logoSize = new LinearLayout.LayoutParams(dp(105), dp(105));
        logoSize.rightMargin = dp(12);
        top.addView(new AirlineLogoView(this, a.call, iata), logoSize);
        LinearLayout info = column();
        info.setGravity(Gravity.CENTER_VERTICAL);
        TextView number = dot((flight.equals("UNKNOWN") ? "CALLSIGN" : flight) + "  /  " + a.call, 26, 0xffe1fff0);
        number.setAutoSizeTextTypeUniformWithConfiguration(18, 28, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        number.setMaxLines(1);
        number.setHorizontallyScrolling(false);
        info.addView(number, new LinearLayout.LayoutParams(-1, dp(56)));
        TextView airports = dot(route.equals("UNKNOWN-UNKNOWN") || route.equals("UNKNOWN–UNKNOWN") ? "ROUTE UNAVAILABLE" : route, 38, 0xffbaffdf);
        airports.setMaxLines(1);
        airports.setHorizontallyScrolling(false);
        airports.setAutoSizeTextTypeUniformWithConfiguration(20, 40, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        info.addView(airports, new LinearLayout.LayoutParams(-1, dp(72)));
        TextView airline = dot(operatorName(a) + "\nTYPE  " + (a.type.isEmpty() ? "UNKNOWN" : a.type), 18, 0xffa9d7c0);
        airline.setMaxLines(2);
        info.addView(airline);
        top.addView(info, layoutProfile.compactCards
            ? new LinearLayout.LayoutParams(-1, -2)
            : new LinearLayout.LayoutParams(0, -1, 1));
        card.addView(top, layoutProfile.scrollCards
            ? new LinearLayout.LayoutParams(-1, -2)
            : new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout metricsRow = new LinearLayout(this);
        if (layoutProfile.compactCards) metricsRow.setOrientation(LinearLayout.VERTICAL);
        String[] names = {"DIST KM", "ALT FT", "SPD KT", "TRK °"};
        String[] values = {
          String.format(Locale.US, "%.1f", Aircraft.distance(prefs.lat(), prefs.lon(), a.lat, a.lon)),
          fmt(a.alt / .3048, ""), fmt(a.speed, ""), fmt(a.heading, "")
        };
        for (int n = 0; n < names.length; n++) {
          LinearLayout metric = column();
          metric.addView(dot(names[n], 13, 0xffa9d7c0));
          TextView value = dot(values[n], 28, 0xffe1fff0);
          value.setMaxLines(1);
          value.setHorizontallyScrolling(false);
          value.setAutoSizeTextTypeUniformWithConfiguration(18, 30, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
          metric.addView(value, new LinearLayout.LayoutParams(-1, dp(54)));
          if (layoutProfile.compactCards) {
            if (n % 2 == 0) {
              LinearLayout pair = new LinearLayout(this);
              metricsRow.addView(pair, new LinearLayout.LayoutParams(-1, -2));
            }
            ((LinearLayout) metricsRow.getChildAt(metricsRow.getChildCount()-1)).addView(metric, new LinearLayout.LayoutParams(0, -2, 1));
          } else metricsRow.addView(metric, new LinearLayout.LayoutParams(0, -2, 1));
        }
        card.addView(metricsRow);
        card.setOnClickListener(v -> details(a));
        cards.addView(card, layoutProfile.scrollCards
            ? new LinearLayout.LayoutParams(-1, -2)
            : new LinearLayout.LayoutParams(-1, 0, 1));
        continue;
      }
      LinearLayout row = new LinearLayout(this);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(dp(4), dp(3), dp(4), dp(3));
      android.graphics.drawable.GradientDrawable border =
          new android.graphics.drawable.GradientDrawable();
      border.setColor(Color.BLACK);
      border.setStroke(dp(1), k == 0 ? 0xff5be6af : 0xff1e4938);
      row.setBackground(border);
      row.addView(
          new AirlineLogoView(this, a.call, iata),
          new LinearLayout.LayoutParams(dp(single ? 150 : 78), -1));
      LinearLayout identity = column();
      TextView flightLine =
          dot(
              single
                  ? flight + "\nCALL  " + a.call
                  : String.format(Locale.US, "%02d  %s  /  %s", k + 1, flight, a.call),
              single ? 17 : 14,
              0xffc7ffe8);
      flightLine.setMaxLines(single ? 2 : 1);
      if (!single) {
        flightLine.setSingleLine(true);
        flightLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
      }
      TextView routeLine =
          dot(
              single ? route + "\n" + operatorName(a) : route + " / " + (a.type.isEmpty() ? "UNKNOWN" : a.type) + " / " + operatorName(a),
              single ? 14 : 11,
              0xff76b99d);
      routeLine.setMaxLines(single ? 2 : 1);
      if (!single) {
        routeLine.setSingleLine(true);
        routeLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
      }
      identity.addView(flightLine, new LinearLayout.LayoutParams(-1, 0, 1));
      identity.addView(routeLine, new LinearLayout.LayoutParams(-1, 0, 1));
      row.addView(identity, new LinearLayout.LayoutParams(0, -1, single ? 1.2f : 1.45f));
      double distance = Aircraft.distance(prefs.lat(), prefs.lon(), a.lat, a.lon);
      String metrics =
          single
              ? String.format(
                  Locale.US,
                  "DIST  %05.1f KM\nALT   %s\nSPD   %s\nHDG   %s",
                  distance,
                  fmt(a.alt / .3048, "FT"),
                  fmt(a.speed, "KT"),
                  fmt(a.heading, "°"))
              : String.format(
                  Locale.US,
                  "%05.1f KM   ALT %s\nSPD %s   TRK %s",
                  distance,
                  fmt(a.alt / .3048, "FT"),
                  fmt(a.speed, "KT"),
                  fmt(a.heading, "°"));
      row.addView(
          dot(metrics, single ? 14 : 12, a.emergency() ? 0xffff657d : 0xff94d8bb),
          new LinearLayout.LayoutParams(0, -1, single ? .9f : 1));
      row.setOnClickListener(v -> details(a));
      if (layoutProfile.compactCards) {
        row.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < row.getChildCount(); i++) {
          View child = row.getChildAt(i);
          child.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(i == 0 ? 64 : 76)));
        }
      }
      cards.addView(row, layoutProfile.scrollCards
          ? new LinearLayout.LayoutParams(-1, layoutProfile.compactCards ? -2 : dp(110))
          : new LinearLayout.LayoutParams(-1, 0, 1));
    }
  }

  void layers() {
    new AlertDialog.Builder(this)
        .setTitle("雷达图层")
        .setMultiChoiceItems(
            new String[] {"ATC 向量和航迹", "地图", "降雨回波", "显示地面飞机（全局）"},
            new boolean[] {
              prefs.b("atc", true),
              prefs.b("map", true),
              prefs.b("echo", false),
              prefs.b("ground", true)
            },
            (d, k, on) -> {
              String key = new String[] {"atc", "map", "echo", "ground"}[k];
              prefs.put(key, on);
              if (key.equals("ground")) showPage();
              if (k == 1) loadMap();
              if (k == 2) {
                lastWeather = 0;
                refreshWeather();
              }
              if (radar != null) radar.invalidate();
            })
        .setPositiveButton("完成", null)
        .show();
  }

  void rangePicker() {
    String[] labels = {"10 km", "25 km", "50 km", "100 km", "250 km", "460 km"};
    double[] values = {10, 25, 50, 100, 250, 460};
    new AlertDialog.Builder(this)
        .setTitle("扫描范围（也可双指缩放）")
        .setItems(
            labels,
            (d, i) -> {
              commitRange(values[i]);
            })
        .show();
  }

  void chooseAircraft(java.util.List<Aircraft> choices) {
    if (choices.size() == 1) {
      details(choices.get(0));
      return;
    }
    String[] labels = new String[choices.size()];
    for (int k = 0; k < labels.length; k++) {
      Aircraft a = choices.get(k);
      labels[k] =
          a.call
              + "  "
              + routes.label(a.call)
              + "  "
              + fmt(a.alt / .3048, "ft")
              + "  "
              + fmt(a.speed, "kt");
    }
    new AlertDialog.Builder(this)
        .setTitle("此处有多架飞机")
        .setItems(labels, (d, k) -> details(choices.get(k)))
        .setNegativeButton("取消", null)
        .show();
  }

  static String fmt(double v, String unit) {
    return Double.isFinite(v) ? String.format(Locale.US, "%.0f %s", v, unit) : "—";
  }

  void commitRange(double value) {
    if (!Double.isFinite(value)) return;
    prefs.put("range", "" + Math.max(10, Math.min(460, value)));
    generation++;
    echo = null;
    mapKey = "";
    nextFetch = 0;
    if (active) {
      loadMap();
      fetch();
      if (prefs.b("echo", false)) {
        lastWeather = 0;
        refreshWeather();
      }
    }
    if (radar != null) radar.invalidate();
  }

  void resetArea() {
    generation++;
    streets.key = "";
    aircraft = new ArrayList<>();
    map = null;
    echo = null;
    mapKey = "";
    lastFetch = lastWeather = 0;
    nextFetch = 0;
    showPage();
    loadMap();
    fetch();
    refreshWeather();
  }

  public void onConfigurationChanged(Configuration c) {
    super.onConfigurationChanged(c);
    build();
  }

  protected void onResume() {
    super.onResume();
    active = true;
    AlarmReceiver.schedule(this);
    started = lastTouch = System.currentTimeMillis();
    if (radar != null) {
      radar.running = true;
      radar.invalidate();
    }
    applyDisplay();
    handler.removeCallbacks(tick);
    handler.post(tick);
    loadMap();
  }

  protected void onPause() {
    active = false;
    if (positions != null) positions.cancel();
    if (radar != null) radar.running = false;
    handler.removeCallbacks(tick);
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    super.onPause();
  }

  protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    net.shutdownNow();
    if (logos != null) logos.downloads.shutdownNow();
    super.onDestroy();
  }

  public boolean dispatchTouchEvent(android.view.MotionEvent e) {
    lastTouch = System.currentTimeMillis();
    return super.dispatchTouchEvent(e);
  }

  final Runnable tick =
      new Runnable() {
        public void run() {
          if (!active) return;
          long now = System.currentTimeMillis();
          routes.prefetch(aircraft);
          streets.refresh();
          if (bigClock != null)
            bigClock.setText(
                new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
          clock.setText(
              new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
          if (!fetching && now >= nextFetch) fetch();
          if (now - lastWeather > 600000) refreshWeather();
          Aircraft.conflicts(aircraft, now);
          long age = lastFetch == 0 ? -1 : (now - lastFetch) / 1000;
          status.setText(
              source
                  + " · "
                  + aircraft.size()
                  + " 架 · "
                  + (age < 0 ? "尚未获取数据" : age + " 秒前更新")
                  + (fetching
                      ? " · 更新中"
                      : " · " + Math.max(0, (nextFetch - now + 999) / 1000) + (error.isEmpty() ? " 秒后刷新" : " 秒后重试"))
                  + (age > Math.max(90, prefs.i("poll", 15) * 3) ? " · 数据源已陈旧" : "")
                  + (error.isEmpty() ? "" : " · " + error)
                  + (prefs.b("map", true) && prefs.b("streets", true) && !streets.status.isEmpty()
                      ? " · " + streets.status
                      : ""));
          if (page.equals("天气时钟")) {
            metar.refresh();
            updateMetar();
          }
          if (radar != null && page.equals("雷达")) radar.invalidate();
          applyDisplay();
          handler.postDelayed(this, 1000);
        }
      };

  void applyDisplay() {
    if (prefs == null) return;
    boolean keep = prefs.b("keep", false);
    if (keep && active) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    long now = System.currentTimeMillis();
    boolean protect = prefs.b("protect", true);
    WindowManager.LayoutParams lp = getWindow().getAttributes();
    lp.screenBrightness = prefs.i("brightness", 30) / 100f;
    if (protect && now - lastTouch > 60000)
      lp.screenBrightness = Math.min(lp.screenBrightness, .12f);
    getWindow().setAttributes(lp);
    if (root != null) {
      int k = (int) (now / 30000);
      root.setTranslationX(protect ? dp((k % 5 - 2) * 2) : 0);
      root.setTranslationY(protect ? dp(((k / 5) % 5 - 2) * 2) : 0);
    }
    if (rest != null) {
      int minutes = prefs.i("restMinutes", 30);
      boolean black =
          keep
              && protect
              && minutes > 0
              && (now - started) % (minutes * 60000L + 60000) >= minutes * 60000L;
      rest.setVisibility(black ? View.VISIBLE : View.GONE);
      if (black) {
        lp.screenBrightness = .01f;
        getWindow().setAttributes(lp);
      }
    }
  }

  void fetch() {
    if (fetching || !active) return;
    fetching = true;
    int gen = generation;
    String wanted = prefs.s("source", "adsb.lol");
    net.execute(
        () -> {
          List<Aircraft> result = null;
          String used = wanted, err = "";
          boolean cooling = false;
          String[] sources =
              wanted.equals("自动") ? new String[] {"adsb.lol", "OpenSky"} : new String[] {wanted};
          for (String s : sources)
            try {
              result = api.flights(prefs, s);
              used = s;
              break;
            } catch (Exception e) {
              used = s;
              cooling = e instanceof Api.Cooling;
              err = e.getMessage() == null ? "网络连接失败" : e.getMessage();
            }
          List<Aircraft> done = result;
          String finalSource = used, finalError = err;
          boolean finalCooling = cooling;
          runOnUiThread(
              () -> {
                fetching = false;
                if (isDestroyed() || gen != generation) return;
                long now = System.currentTimeMillis();
                if (done != null) {
                  Map<String, Aircraft> old = new HashMap<>();
                  for (Aircraft a : aircraft) old.put(a.hex, a);
                  for (Aircraft a : done) {
                    Aircraft o = old.get(a.hex);
                    if (o != null && now - o.seen < 120000) {
                      a.trail.addAll(o.trail);
                      if (a.seen > o.seen) a.trail.add(new double[] {o.lat, o.lon});
                      while (a.trail.size() > 8) a.trail.remove(0);
                    }
                  }
                  aircraft = done;
                  lastFetch = now;
                  failures = 0;
                  source = finalSource;
                  error = "";
                  if (page.equals("最近航班")) showPage();
                } else {
                  if (!finalCooling) failures++;
                  source = finalSource;
                  error = finalError;
                }
                int interval = Math.max(30, prefs.i("poll", 30));
                if (finalSource.equals("OpenSky")) {
                  double area =
                      4
                          * Math.pow(prefs.range() / 110.574, 2)
                          / Math.max(.05, Math.cos(Math.toRadians(prefs.lat())));
                  int cost = area <= 25 ? 1 : area <= 100 ? 2 : area <= 400 ? 3 : 4;
                  interval =
                      Math.max(interval, (prefs.secret("clientId").isEmpty() ? 240 : 30) * cost);
                }
                long delay = done == null ? (finalCooling ? 0 : Api.retryDelay(failures)) : interval * 1000L;
                long allowed = api.readyAt(prefs, finalSource);
                nextFetch = Math.max(now + Math.max(done == null ? 1000 : 0, delay), allowed);
                if (done == null && allowed > now + delay)
                  error = prefs.s("cooldownReason_" + finalSource, finalError);
              });
        });
  }

  void loadMap() {
    if (!prefs.b("map", true)) return;
    String key = prefs.lat() + ":" + prefs.lon() + ":" + prefs.range();
    if (key.equals(mapKey)) return;
    mapKey = key;
    double la = prefs.lat(), lo = prefs.lon(), range = prefs.range();
    int gen = generation;
    net.execute(
        () -> {
          try {
            MapData m = MapData.load(this, la, lo, range);
            runOnUiThread(
                () -> {
                  if (gen == generation && !isDestroyed()) {
                    map = m;
                    if (radar != null) radar.invalidate();
                  }
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (gen == generation) {
                    mapKey = "";
                    toast("底图加载失败，可稍后在设置重试");
                  }
                });
          }
        });
  }

  void refreshWeather() {
    if (!active) return;
    long now = System.currentTimeMillis();
    if (now - lastWeather < 15000) return;
    lastWeather = now;
    double la = prefs.lat(), lo = prefs.lon(), range = prefs.range();
    int gen = generation;
    boolean rain = prefs.b("echo", false);
    net.execute(
        () -> {
          try {
            JSONObject c =
                Api.json(
                        String.format(
                            Locale.US,
                            "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f&current=temperature_2m,relative_humidity_2m,wind_speed_10m,wind_direction_10m",
                            la,
                            lo),
                        "",
                        null)
                    .getJSONObject("current");
            String info =
                "TEMP  "
                    + fmt(c.optDouble("temperature_2m"), "°C")
                    + "\nRH    "
                    + fmt(c.optDouble("relative_humidity_2m"), "%")
                    + "\nWIND  "
                    + fmt(c.optDouble("wind_speed_10m"), "KM/H")
                    + "  "
                    + fmt(c.optDouble("wind_direction_10m"), "°")
                    + "\nUPDATE  "
                    + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
            runOnUiThread(
                () -> {
                  if (gen == generation) {
                    weatherInfo = info;
                    if (weather != null) weather.setText(info);
                  }
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  weatherInfo = "天气连接失败 · " + weatherInfo;
                  if (weather != null) weather.setText(weatherInfo);
                });
          }
          if (rain)
            try {
              JSONObject j =
                  Api.json("https://api.rainviewer.com/public/weather-maps.json", "", null);
              JSONArray frames = j.getJSONObject("radar").getJSONArray("past");
              JSONObject f = frames.getJSONObject(frames.length() - 1);
              int z =
                  Math.min(
                      7,
                      Math.max(
                          1,
                          (int)
                              Math.floor(
                                  Math.log(40075 * Math.cos(Math.toRadians(la)) / range)
                                      / Math.log(2))));
              double km = 40075 * Math.cos(Math.toRadians(la)) / Math.pow(2, z) * 2;
              String host = j.getString("host");
              if (!host.equals("https://tilecache.rainviewer.com"))
                throw new IOException("雷达瓦片来源异常");
              byte[] b =
                  Api.request(
                      host
                          + f.getString("path")
                          + String.format(Locale.US, "/512/%d/%.5f/%.5f/2/1_1.png", z, la, lo),
                      "",
                      null,
                      "");
              Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length);
              if (bm == null) throw new IOException("回波解码失败");
              runOnUiThread(
                  () -> {
                    if (gen == generation) {
                      echo = bm;
                      echoKm = km;
                    }
                  });
            } catch (Exception e) {
              runOnUiThread(
                  () -> {
                    if (gen == generation) {
                      echo = null;
                      toast("降雨回波不可用，请稍后重试");
                    }
                  });
            }
        });
  }

  void details(Aircraft a) {
    if (radar != null) {
      radar.selected = a.hex;
      radar.invalidate();
    }
    if (selection != null)
      selection.setText(
          a.call + "  " + a.type + "\n" + fmt(a.alt / .3048, "ft") + " · " + fmt(a.speed, "kt"));
    LinearLayout col = column();
    TextView info =
        text(
            a.call
                + "  "
                + a.hex.toUpperCase(Locale.ROOT)
                + "\n注册号 "
                + (a.reg.isEmpty() ? "—" : a.reg)
                + "\n高度 "
                + fmt(a.alt / .3048, "ft")
                + "  速度 "
                + fmt(a.speed, "kt")
                + "\n地面航迹方向 "
                + fmt(a.heading, "°")
                + "  垂直速度 "
                + fmt(a.vr, "ft/min")
                + "\n距离 "
                + fmt(Aircraft.distance(prefs.lat(), prefs.lon(), a.lat, a.lon), "km")
                + "  应答机 "
                + a.squawk
                + (a.emergency() ? " 紧急代码" : ""),
            16,
            a.emergency() ? 0xffff7187 : 0xffb9d6c6);
    col.addView(info);
    TextView route = text(routes.label(a.call) + " · 航线查询中…", 14, 0xff89b5a0);
    col.addView(route);
    col.addView(button("机型图鉴 · " + (a.type.isEmpty() ? "查询机型" : a.type), () -> airframe(a)));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(col);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("航班详情 · 点击空白可取消高亮")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .setNeutralButton(
                "取消高亮",
                (d, which) -> {
                  if (radar != null) {
                    radar.selected = "";
                    radar.invalidate();
                  }
                })
            .create();
    dialog.show();
    routes.request(a.call, route::setText);
  }

  void airframe(Aircraft a) {
    if (a.type.isEmpty()) {
      toast("正在按 ICAO 查询机型…");
      net.execute(
          () -> {
            try {
              JSONObject r =
                  Api.json("https://api.adsbdb.com/v0/aircraft/" + Api.enc(a.hex), "", null)
                      .getJSONObject("response")
                      .getJSONObject("aircraft");
              String type = r.optString("icao_type"), reg = r.optString("registration");
              runOnUiThread(
                  () -> {
                    a.type = type;
                    a.reg = reg;
                    if (type.isEmpty()) toast("数据源未提供此飞机的机型");
                    else airframe(a);
                  });
            } catch (Exception e) {
              runOnUiThread(() -> toast("没有查到机型资料"));
            }
          });
      return;
    }
    JSONObject spec = db == null ? null : db.optJSONObject("specs").optJSONObject(a.type);
    if (spec == null) {
      new AlertDialog.Builder(this)
          .setTitle(a.type)
          .setMessage("离线图鉴未收录此机型。\n注册号：" + a.reg)
          .setPositiveButton("关闭", null)
          .show();
      return;
    }
    LinearLayout col = column();
    int sil = spec.optInt("sil", -1);
    if (sil >= 0 && sil < 107 && silhouettes != null) {
      Bitmap bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
      int[] px = new int[9216];
      for (int k = 0; k < px.length; k++)
        px[k] = ((silhouettes[sil * 9216 + k] & 255) << 24) | 0x00ffcd64;
      bmp.setPixels(px, 0, 96, 0, 0, 96, 96);
      AirframeView im = new AirframeView(this, bmp, spec.optInt("span_dm") / 10f);
      col.addView(im, new LinearLayout.LayoutParams(-1, dp(220)));
    }
    String country = a.country;
    try {
      long hex = Long.parseLong(a.hex, 16);
      JSONArray blocks = db.getJSONArray("blocks");
      for (int k = 0; k < blocks.length(); k++) {
        JSONArray r = blocks.getJSONArray(k);
        if (hex >= r.getLong(0) && hex <= r.getLong(1)) {
          country = r.getString(2);
          break;
        }
      }
    } catch (Exception ignored) {
    }
    JSONArray op =
        db.optJSONObject("operators")
            .optJSONArray(a.call.substring(0, Math.min(3, a.call.length())));
    col.addView(
        text(
            spec.optString("mfr")
                + " · "
                + spec.optString("model")
                + "\n翼展 "
                + spec.optInt("span_dm") / 10.0
                + " m   长度 "
                + spec.optInt("len_dm") / 10.0
                + " m\n最大起飞重量 "
                + spec.optInt("mtow_100kg") / 10.0
                + " t\n巡航 "
                + spec.optInt("cruise_kt")
                + " kt   发动机 "
                + spec.optString("eng")
                + " × "
                + spec.optInt("eng_n")
                + "\n注册号 "
                + a.reg
                + " · "
                + country
                + "\n运营方 "
                + (op == null ? "—" : op.optString(0))
                + "\n资料为机型参考值，0 表示未收录",
            15,
            0xffbbd7c6));
    ScrollView sc = new ScrollView(this);
    sc.addView(col);
    new AlertDialog.Builder(this)
        .setTitle(a.type + " · 离线机型图鉴")
        .setView(sc)
        .setPositiveButton("关闭", null)
        .show();
  }

  EditText field(LinearLayout parent, String label, String value, boolean secret) {
    parent.addView(text(label, 13, 0xff7baf97));
    EditText e = new EditText(this);
    e.setSingleLine(true);
    e.setText(value);
    e.setTextSize(15);
    e.setPadding(dp(10), dp(8), dp(10), dp(8));
    if (secret) e.setInputType(129);
    parent.addView(e, new LinearLayout.LayoutParams(-1, -2));
    return e;
  }

  void toggle(LinearLayout col, String label, String key, boolean def) {
    Switch s = new Switch(this);
    s.setText(label);
    s.setTextSize(14);
    s.setPadding(dp(10), dp(12), dp(10), dp(12));
    s.setChecked(prefs.b(key, def));
    s.setOnCheckedChangeListener(
        (b, v) -> {
          prefs.put(key, v);
          if (key.equals("ground")) showPage();
          applyDisplay();
        });
    col.addView(s);
  }

  void updateMetar() {
    if (metarText != null && page.equals("天气时钟")) metarText.setText(metar.display());
  }

  void chooseMetar() {
    LinearLayout col = column();
    EditText query = field(col, "ICAO / IATA / 机场名称（如 YMML / MEL）", "", false);
    col.addView(
        button(
            "自动选择最近报告站",
            () -> {
              prefs.put("metarStation", "");
              metar.next = 0;
              metar.refresh();
              updateMetar();
            }));
    LinearLayout results = column();
    Runnable search =
        () -> {
          results.removeAllViews();
          java.util.List<JSONObject> candidates = metar.nearest(query.getText().toString());
          if (candidates.isEmpty()) results.addView(text("未找到机场，请试 ICAO 四字代码或英文名", 14, 0xff8aa897));
          for (JSONObject airport : candidates)
            results.addView(
                button(
                    airport.optString("id")
                        + " / "
                        + airport.optString("iata")
                        + " · "
                        + airport.optString("name")
                        + " · "
                        + fmt(
                            Aircraft.distance(
                                prefs.lat(),
                                prefs.lon(),
                                airport.optDouble("lat"),
                                airport.optDouble("lon")),
                            "km"),
                    () -> {
                      prefs.put("metarStation", airport.optString("id"));
                      metar.next = 0;
                      metar.refresh();
                      updateMetar();
                      toast("已选择 " + airport.optString("id"));
                    }));
        };
    col.addView(button("搜索机场", search));
    col.addView(results);
    search.run();
    ScrollView sc = new ScrollView(this);
    sc.addView(col);
    new AlertDialog.Builder(this)
        .setTitle("METAR 机场（距离以雷达中心计算）")
        .setView(sc)
        .setNegativeButton("完成", null)
        .show();
  }

  public void onRequestPermissionsResult(int request, String[] names, int[] grants) {
    super.onRequestPermissionsResult(request, names, grants);
    if (request == 71
        && grants.length > 0
        && grants[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) positions.current();
    else if (request == 71) toast("未获得定位权限，仍可使用地址或经纬度");
  }

  void settings() {
    LinearLayout col = column();
    col.setPadding(dp(10), 0, dp(10), 0);
    toggle(col, "显示地面飞机（雷达及最近航班）", "ground", true);
    toggle(col, "保持不熄屏（仅在应用前台）", "keep", false);
    toggle(col, "防烧屏：内容位移 / 闲置调暗 / 定时黑屏", "protect", true);
    col.addView(text("每 30 秒移动整个界面；闲置 1 分钟调暗；定时黑屏 1 分钟，触摸恢复。只能降低烧屏风险。", 12, 0xff8aa897));
    col.addView(text("应用亮度", 13, 0xff7baf97));
    SeekBar bright = new SeekBar(this);
    bright.setMax(45);
    bright.setProgress(prefs.i("brightness", 30) - 5);
    bright.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar b, int v, boolean u) {
            if (u) {
              prefs.put("brightness", v + 5);
              applyDisplay();
            }
          }

          public void onStartTrackingTouch(SeekBar b) {}

          public void onStopTrackingTouch(SeekBar b) {}
        });
    col.addView(bright);
    EditText restMinutes = field(col, "每隔多少分钟黑屏休息（5–120）", "" + prefs.i("restMinutes", 30), false);
    toggle(col, "横屏优先", "landscape", true);
    col.addView(
        button(
            "中心位置 · 定位 / 地址 / 经纬度",
            () -> {
              if (settingsDialog != null) settingsDialog.dismiss();
              positions.show();
            }));
    toggle(col, "街道底图（OpenStreetMap）", "streets", true);
    toggle(col, "跑道延长线", "extensions", false);
    toggle(col, "显示机场", "airports", true);
    toggle(col, "显示跑道及延长线", "runways", true);
    toggle(col, "显示导航点", "fixes", false);
    toggle(col, "显示空域", "airspace", true);
    EditText lat = field(col, "中心纬度（-90 至 90）", "" + prefs.lat(), false),
        lon = field(col, "中心经度（-180 至 180）", "" + prefs.lon(), false),
        range = field(col, "数据覆盖半径 km（10–460）", "" + prefs.range(), false);
    col.addView(text("数据源", 13, 0xff7baf97));
    Spinner src = new Spinner(this);
    String[] names = {"adsb.lol", "OpenSky", "airplanes.live", "自动"};
    src.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
    src.setSelection(Math.max(0, Arrays.asList(names).indexOf(prefs.s("source", "adsb.lol"))));
    col.addView(src);
    EditText poll = field(col, "刷新间隔秒（30–900；限流单独退避）", "" + prefs.i("poll", 30), false);
    EditText cid = field(col, "OpenSky Client ID（可选）", prefs.secret("clientId"), true),
        secret = field(col, "OpenSky Client Secret（可选）", prefs.secret("clientSecret"), true);
    col.addView(
        text(
            "adsb.lol 无需填写密钥。airplanes.live 当前实测要求联系其团队授权。OpenSky 有每日积分限制；密钥仅用 Android Keystore"
                + " 加密保存在本机。",
            12,
            0xff8aa897));
    col.addView(
        button("Wi-Fi 网络设置", () -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS))));
    col.addView(
        button(
            "应用与闹钟权限",
            () -> {
              startActivity(
                  new Intent(
                      Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                      Uri.parse("package:" + getPackageName())));
            }));
    col.addView(button("查看许可证与数据来源", this::about));
    ScrollView sc = new ScrollView(this);
    sc.addView(col);
    AlertDialog d =
        new AlertDialog.Builder(this)
            .setTitle("设置")
            .setView(sc)
            .setNegativeButton("关闭", null)
            .setPositiveButton("保存", null)
            .create();
    d.setOnShowListener(
        x ->
            d.getButton(-1)
                .setOnClickListener(
                    v -> {
                      try {
                        double la = Double.parseDouble(lat.getText().toString()),
                            lo = Double.parseDouble(lon.getText().toString()),
                            ra = Double.parseDouble(range.getText().toString());
                        int po = Integer.parseInt(poll.getText().toString()),
                            rm = Integer.parseInt(restMinutes.getText().toString());
                        if (!Double.isFinite(la)
                            || !Double.isFinite(lo)
                            || !Double.isFinite(ra)
                            || Math.abs(la) > 90
                            || Math.abs(lo) > 180
                            || ra < 10
                            || ra > 460
                            || po < 30
                            || po > 900
                            || rm < 5
                            || rm > 120) throw new IllegalArgumentException();
                        prefs.secret("clientId", cid.getText().toString().trim());
                        prefs.secret("clientSecret", secret.getText().toString().trim());
                        prefs.put("lat", "" + la);
                        prefs.put("lon", "" + lo);
                        prefs.put("range", "" + ra);
                        prefs.put("poll", po);
                        prefs.put("restMinutes", rm);
                        prefs.put("source", names[src.getSelectedItemPosition()]);
                        api.expires = 0;
                        setRequestedOrientation(
                            prefs.b("landscape", true)
                                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                : ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
                        d.dismiss();
                        resetArea();
                      } catch (Exception ex) {
                        toast("保存失败，请检查数值范围与密钥存储状态");
                      }
                    }));
    settingsDialog = d;
    d.show();
    Rect bounds = getWindowManager().getCurrentWindowMetrics().getBounds();
    d.getWindow().setLayout(Math.min(dp(720), (int) (bounds.width() * .9f)), (int) (bounds.height() * .9f));
    d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
  }

  void alarmPage() {
    ScrollView sc = new ScrollView(this);
    LinearLayout col = column();
    sc.addView(col);
    body.addView(sc, new LinearLayout.LayoutParams(-1, -1));
    boolean exact =
        Build.VERSION.SDK_INT < 31 || getSystemService(AlarmManager.class).canScheduleExactAlarms();
    col.addView(text(exact ? "4 组每周闹钟 · 本机响铃" : "需要开启“闹钟和提醒”权限，才会安排定时响铃。", 14, 0xffa5d4ba));
    if (!exact)
      col.addView(
          button(
              "开启精确闹钟权限",
              () ->
                  startActivity(
                      new Intent(
                          Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                          Uri.parse("package:" + getPackageName())))));
    if (Build.VERSION.SDK_INT >= 33
        && !getSystemService(NotificationManager.class).areNotificationsEnabled())
      col.addView(
          button(
              "开启闹钟通知",
              () ->
                  requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 11)));
    for (int k = 0; k < 4; k++) {
      final int slot = k;
      LinearLayout row = new LinearLayout(this);
      boolean enabled = prefs.b("alarm" + k, false);
      row.addView(
          button(
              "A" + (k + 1) + (enabled ? "  ON" : "  OFF"),
              () -> {
                boolean on = !prefs.b("alarm" + slot, false);
                prefs.put("alarm" + slot, on);
                if (!AlarmReceiver.schedule(this) && on) toast("请先开启精确闹钟权限");
                showPage();
              }),
          new LinearLayout.LayoutParams(0, dp(56), 1));
      row.addView(
          button(
              String.format(
                  Locale.US, "%02d:%02d", prefs.i("hour" + k, 7), prefs.i("minute" + k, 0)),
              () ->
                  new TimePickerDialog(
                          this,
                          (t, h, m) -> {
                            prefs.put("hour" + slot, h);
                            prefs.put("minute" + slot, m);
                            AlarmReceiver.schedule(this);
                            showPage();
                          },
                          prefs.i("hour" + slot, 7),
                          prefs.i("minute" + slot, 0),
                          true)
                      .show()));
      row.addView(button("重复日期", () -> alarmOptions(slot)));
      col.addView(row);
    }
    col.addView(
        button("测试本机铃声（可停止）", () -> startForegroundService(new Intent(this, AlarmService.class))));
    col.addView(button("停止本机铃声", () -> stopService(new Intent(this, AlarmService.class))));
    col.addView(text("手机强行停止应用后，系统会取消闹钟。小米后台管理需允许应用自启动；系统重启后会重新安排。", 12, 0xff719783));
  }

  void alarmOptions(int slot) {
    LinearLayout col = column();
    boolean[] days = new boolean[7];
    String[] labels = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    int mask = prefs.i("days" + slot, 127);
    for (int n = 0; n < 7; n++) {
      int day = n;
      CheckBox c = new CheckBox(this);
      c.setText(labels[n]);
      days[n] = (mask & (1 << n)) != 0;
      c.setChecked(days[n]);
      c.setOnCheckedChangeListener((b, v) -> days[day] = v);
      col.addView(c);
    }
    ScrollView sc = new ScrollView(this);
    sc.addView(col);
    new AlertDialog.Builder(this)
        .setTitle("重复日期")
        .setView(sc)
        .setPositiveButton(
            "保存",
            (d, w) -> {
              int m = 0;
              for (int n = 0; n < 7; n++) if (days[n]) m |= 1 << n;
              prefs.put("days" + slot, m);
              AlarmReceiver.schedule(this);
            })
        .setNegativeButton("取消", null)
        .show();
  }

  void screenshot() {
    try {
      Bitmap b = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
      root.draw(new Canvas(b));
      File f = new File(getCacheDir(), "radar.png");
      try (FileOutputStream out = new FileOutputStream(f)) {
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
      }
      Uri uri = Uri.parse("content://org.flightdesk.radar.shots/radar.png");
      startActivity(
          Intent.createChooser(
              new Intent(Intent.ACTION_SEND)
                  .setType("image/png")
                  .putExtra(Intent.EXTRA_STREAM, uri)
                  .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
              "分享雷达截图"));
    } catch (Exception e) {
      toast("截图失败");
    }
  }

  void about() {
    new AlertDialog.Builder(this)
        .setTitle("Flight Radar Android 0.4.1")
        .setMessage(
            "由 delphicchen/esp32_flight_radar 改编，Android 界面和系统集成已重写。\n\n"
                + "CC BY-NC-SA 4.0：署名、非商业、相同方式共享。\n"
                + "机型轮廓：plane-watch/pw-silhouettes\n"
                + "机型与运营方：rikgale/ICAOList\n"
                + "性能：TU Delft OpenAP 与原项目补充资料\n"
                + "地图：delphicchen/flight-radar-maps（上游来源包括 Natural Earth / OurAirports / 上游区域资料）\n"
                + "航班：adsb.lol / OpenSky / airplanes.live\n"
                + "航线：adsbdb\n"
                + "天气：Open-Meteo / RainViewer\n\n"
                + "仅供兴趣展示，不能用于导航或真实空管。接口的可用性、额度和使用条款分别由数据提供方决定。")
        .setPositiveButton("关闭", null)
        .setNeutralButton(
            "原项目",
            (d, w) ->
                startActivity(
                    new Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/delphicchen/esp32_flight_radar"))))
        .show();
  }
}
