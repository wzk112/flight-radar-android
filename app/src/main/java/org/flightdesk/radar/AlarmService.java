package org.flightdesk.radar;

import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;
import org.json.*;

public class AlarmService extends Service {
  static volatile boolean playing = false;
  MediaPlayer player;
  final Handler h = new Handler(Looper.getMainLooper());

  public IBinder onBind(Intent i) {
    return null;
  }

  public int onStartCommand(Intent i, int flags, int id) {
    if (i != null && "stop".equals(i.getAction())) {
      stopSelf();
      return START_NOT_STICKY;
    }
    int slot = i == null ? 0 : i.getIntExtra("slot", 0);
    NotificationManager n = getSystemService(NotificationManager.class);
    NotificationChannel channel =
        new NotificationChannel("alarm", "雷达闹钟", NotificationManager.IMPORTANCE_HIGH);
    channel.setSound(null, null);
    n.createNotificationChannel(channel);
    PendingIntent stop =
        PendingIntent.getService(
            this,
            1,
            new Intent(this, AlarmService.class).setAction("stop"),
            PendingIntent.FLAG_IMMUTABLE);
    startForeground(
        41,
        new Notification.Builder(this, "alarm")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Flight Radar · 闹钟 " + (slot + 1))
            .setContentText("点击停止铃声")
            .setOngoing(true)
            .setContentIntent(stop)
            .addAction(new Notification.Action.Builder(null, "停止", stop).build())
            .build());
    if (player != null) {
      player.release();
      player = null;
    }
    try {
      player = new MediaPlayer();
      player.setAudioAttributes(
          new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_ALARM)
              .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
              .build());
      player.setDataSource(
          this,
          android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM));
      player.setLooping(true);
      player.prepare();
      player.start();
      playing = player.isPlaying();
    } catch (Exception e) {
      stopSelf();
    }
    h.removeCallbacksAndMessages(null);
    h.postDelayed(this::stopSelf, 5 * 60 * 1000);
    return START_NOT_STICKY;
  }

  public void onDestroy() {
    playing = false;
    h.removeCallbacksAndMessages(null);
    if (player != null) {
      player.release();
      player = null;
    }
    super.onDestroy();
  }
}
