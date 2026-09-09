package org.flightdesk.radar;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;

public class ShotProvider extends ContentProvider {
  public boolean onCreate() {
    return true;
  }

  public String getType(Uri u) {
    return "image/png";
  }

  public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
    if (!"r".equals(mode) || !"radar.png".equals(u.getLastPathSegment()))
      throw new FileNotFoundException();
    return ParcelFileDescriptor.open(
        new File(getContext().getCacheDir(), "radar.png"), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  public Cursor query(Uri u, String[] p, String s, String[] a, String order) {
    MatrixCursor c = new MatrixCursor(new String[] {"_display_name", "_size"});
    c.addRow(
        new Object[] {"radar.png", new File(getContext().getCacheDir(), "radar.png").length()});
    return c;
  }

  public Uri insert(Uri u, ContentValues v) {
    throw new UnsupportedOperationException();
  }

  public int delete(Uri u, String s, String[] a) {
    return 0;
  }

  public int update(Uri u, ContentValues v, String s, String[] a) {
    return 0;
  }
}
