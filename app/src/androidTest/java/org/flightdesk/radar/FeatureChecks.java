package org.flightdesk.radar;

import android.app.*;
import android.location.*;
import android.os.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class FeatureChecks extends Instrumentation {
  public void onCreate(Bundle b) {
    super.onCreate(b);
    start();
  }

  public void onStart() {
    Bundle result = new Bundle();
    StringBuilder log = new StringBuilder();
    MainActivity a = null;
    String saved = "";
    try {
      ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
      getUiAutomation()
          .executeShellCommand("am start -n org.flightdesk.radar/.MainActivity")
          .close();
      a = (MainActivity) waitForMonitorWithTimeout(monitor, 10000);
      if (a == null) throw new AssertionError("activity not opened");
      final MainActivity screen = a;
      saved = a.prefs.s("metarStation", "");
      runOnMainSync(
          () -> {
            screen.prefs.put("metarStation", "YMML");
            screen.page = "天气时钟";
            screen.showPage();
          });
      for (int i = 0; i < 60 && a.metar.report == null; i++) SystemClock.sleep(500);
      if (a.metar.report == null)
        throw new AssertionError("live METAR unavailable: " + a.metar.error);
      log.append("PASS live YMML METAR fetched on phone\n");
      if (!a.metar.report.optString("icaoId").equals("YMML"))
        throw new AssertionError("wrong station");
      log.append("PASS selected airport report matches\n");
      runOnMainSync(
          () -> {
            if (!screen.metarText.getText().toString().contains("RAW METAR"))
              throw new AssertionError("compact raw METAR missing");
          });
      log.append("PASS compact point-matrix METAR content renders\n");
      try (FileOutputStream out =
          new FileOutputStream(new File(a.getFilesDir(), "metar-new.png"))) {
        getUiAutomation()
            .takeScreenshot()
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
      }
      boolean geocoder = Geocoder.isPresent();
      log.append("Geocoder present: ").append(geocoder).append('\n');
      if (geocoder) {
        List<Address> places = a.positions.lookup("Melbourne Australia");
        log.append(
            places != null && !places.isEmpty()
                ? "PASS live address lookup returns a coordinate\n"
                : "ADDRESS_LOOKUP_UNAVAILABLE\n");
      }
      getUiAutomation()
          .adoptShellPermissionIdentity(
              android.Manifest.permission.ACCESS_COARSE_LOCATION,
              android.Manifest.permission.ACCESS_FINE_LOCATION);
      LocationManager lm = a.getSystemService(LocationManager.class);
      log.append("Location enabled: ").append(lm.isLocationEnabled()).append('\n');
      final CountDownLatch latch = new CountDownLatch(1);
      final boolean[] got = {false};
      CancellationSignal cancel = new CancellationSignal();
      String provider =
          lm.isProviderEnabled("fused")
              ? "fused"
              : lm.isProviderEnabled("network") ? "network" : "gps";
      lm.getCurrentLocation(
          provider,
          cancel,
          a.getMainExecutor(),
          v -> {
            got[0] = v != null;
            latch.countDown();
          });
      latch.await(20, TimeUnit.SECONDS);
      cancel.cancel();
      log.append(
          got[0]
              ? "PASS current location received without logging coordinates\n"
              : "LOCATION_FIX_UNAVAILABLE\n");
    } catch (Throwable t) {
      log.append("FAIL ")
          .append(t.getClass().getSimpleName())
          .append(": ")
          .append(t.getMessage())
          .append('\n');
    } finally {
      if (a != null) {
        final MainActivity screen = a;
        final String station = saved;
        runOnMainSync(() -> screen.prefs.put("metarStation", station));
      }
      result.putString("stream", log.toString());
      finish(Activity.RESULT_OK, result);
    }
  }
}
