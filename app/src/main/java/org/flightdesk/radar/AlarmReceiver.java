package org.flightdesk.radar;

import android.app.*;
import android.content.*;
import android.os.Build;
import java.util.*;

public class AlarmReceiver extends BroadcastReceiver {
  public void onReceive(Context c, Intent i) {
    if ("org.flightdesk.ALARM".equals(i.getAction())) {
      int slot = i.getIntExtra("slot", 0);
      c.startForegroundService(new Intent(c, AlarmService.class).putExtra("slot", slot));
    }
    schedule(c);
  }

  static long next(int hour, int min, int mask, long now) {
    Calendar base = Calendar.getInstance();
    base.setTimeInMillis(now);
    for (int n = 0; n < 8; n++) {
      Calendar d = (Calendar) base.clone();
      d.add(Calendar.DAY_OF_YEAR, n);
      d.set(Calendar.HOUR_OF_DAY, hour);
      d.set(Calendar.MINUTE, min);
      d.set(Calendar.SECOND, 0);
      d.set(Calendar.MILLISECOND, 0);
      int day = (d.get(Calendar.DAY_OF_WEEK) + 5) % 7;
      if ((mask & (1 << day)) != 0 && d.getTimeInMillis() > now) return d.getTimeInMillis();
    }
    return -1;
  }

  static boolean schedule(Context c) {
    Prefs p = new Prefs(c);
    AlarmManager m = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    boolean exact = Build.VERSION.SDK_INT < 31 || m.canScheduleExactAlarms();
    for (int k = 0; k < 4; k++) {
      PendingIntent pi =
          PendingIntent.getBroadcast(
              c,
              k,
              new Intent(c, AlarmReceiver.class)
                  .setAction("org.flightdesk.ALARM")
                  .putExtra("slot", k),
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      m.cancel(pi);
      if (!p.b("alarm" + k, false)) continue;
      long at =
          next(
              p.i("hour" + k, 7),
              p.i("minute" + k, 0),
              p.i("days" + k, 127),
              System.currentTimeMillis());
      if (at < 0) continue;
      if (exact)
        m.setAlarmClock(
            new AlarmManager.AlarmClockInfo(
                at,
                PendingIntent.getActivity(
                    c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE)),
            pi);
    }
    return exact;
  }
}
