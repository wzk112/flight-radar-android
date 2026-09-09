package org.flightdesk.radar;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import java.io.*;
import java.util.*;

/** Fixture screenshots across actual Android window sizes; never changes saved location or feeds. */
public class AdaptiveChecks extends Instrumentation {
  String orientation;
  public void onCreate(Bundle args) { super.onCreate(args); orientation=args.getString("orientation", "landscape"); start(); }
  public void onStart() {
    Bundle result = new Bundle();
    MainActivity a = null;
    try {
      ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
      getUiAutomation().executeShellCommand("am start -n org.flightdesk.radar/.MainActivity").close();
      a = (MainActivity) monitor.waitForActivityWithTimeout(10000);
      if (a == null) throw new IllegalStateException("Activity launch failed");
      final MainActivity host = a;
      runOnMainSync(() -> host.setRequestedOrientation(orientation.equals("portrait") ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
      SystemClock.sleep(800);
      runOnMainSync(() -> {
        host.active = false;
        host.generation++;
        host.handler.removeCallbacks(host.tick);
        host.aircraft = new ArrayList<>();
        for (String call : new String[]{"CSS123", "FDX456", "CHH789", "QFA1"}) {
          Aircraft f = new Aircraft(); f.call=call; f.hex=call; f.type="B77L";
          f.lat=host.prefs.lat()+.03; f.lon=host.prefs.lon(); f.alt=11000; f.speed=450; f.heading=123;
          f.seen=System.currentTimeMillis(); host.aircraft.add(f);
        }
      });
      int saved = host.prefs.i("nearestCount",1);
      for (String page : new String[]{"雷达", "single", "multi", "天气时钟", "闹钟"}) {
        runOnMainSync(() -> {
          host.prefs.put("nearestCount",page.equals("multi")?4:1);
          host.page=page.equals("single")||page.equals("multi")?"最近航班":page;
          host.showPage();
        });
        SystemClock.sleep(300);
        Bitmap shot = getUiAutomation().takeScreenshot();
        try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getFilesDir(), "adaptive-"+page+".png"))) {shot.compress(Bitmap.CompressFormat.PNG,100,out);}
        if(host.body.getWidth()<1 || host.body.getHeight()<1) throw new AssertionError("empty body "+page);
      }
      runOnMainSync(() -> host.prefs.put("nearestCount",saved));
      result.putString("stream","Adaptive pages captured successfully\n");
      finish(Activity.RESULT_OK,result);
    } catch(Exception e) {result.putString("stream",e.toString());finish(Activity.RESULT_CANCELED,result);}
  }
}
