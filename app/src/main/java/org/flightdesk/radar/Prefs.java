package org.flightdesk.radar;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

final class Prefs {
  final SharedPreferences p;

  Prefs(Context c) {
    p = c.getSharedPreferences("radar", 0);
  }

  String s(String k, String d) {
    return p.getString(k, d);
  }

  boolean b(String k, boolean d) {
    return p.getBoolean(k, d);
  }

  int i(String k, int d) {
    return p.getInt(k, d);
  }

  double n(String k, double d) {
    try {
      return Double.parseDouble(s(k, "" + d));
    } catch (Exception e) {
      return d;
    }
  }

  void put(String k, String v) {
    p.edit().putString(k, v).apply();
  }

  void put(String k, boolean v) {
    p.edit().putBoolean(k, v).apply();
  }

  void put(String k, int v) {
    p.edit().putInt(k, v).apply();
  }

  double lat() {
    return n("lat", -37.8136);
  }

  double lon() {
    return n("lon", 144.9631);
  }

  double range() {
    return n("range", 100);
  }

  private javax.crypto.SecretKey key() throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    if (!ks.containsAlias("radar-secrets")) {
      KeyGenerator g = KeyGenerator.getInstance("AES", "AndroidKeyStore");
      g.init(
          new KeyGenParameterSpec.Builder(
                  "radar-secrets", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      g.generateKey();
    }
    return (javax.crypto.SecretKey) ks.getKey("radar-secrets", null);
  }

  void secret(String k, String v) throws Exception {
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.ENCRYPT_MODE, key());
    put(
        k,
        Base64.encodeToString(c.getIV(), 2)
            + ":"
            + Base64.encodeToString(
                c.doFinal(v.getBytes(java.nio.charset.StandardCharsets.UTF_8)), 2));
  }

  String secret(String k) {
    try {
      String[] v = s(k, "").split(":");
      if (v.length != 2) return "";
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(v[0], 2)));
      return new String(c.doFinal(Base64.decode(v[1], 2)), java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      return "";
    }
  }
}
