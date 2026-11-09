package ir.poco.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Keeps a small local Telegram notification history encrypted with an
 * Android Keystore AES-GCM key. Notification text is never sent by this store.
 */
public final class NotificationStore {
    private static final String PREFS = "poco_local_notification_store";
    private static final String KEY_ROWS = "telegram_rows";
    private static final String KEY_ROWS_ENCRYPTED = "telegram_rows_encrypted";
    private static final String KEY_ALIAS = "poco_notifications_aes_key";
    private static final int MAX_ROWS = 30;

    private NotificationStore() {}

    public static synchronized void add(Context context, String packageName,
                                         String title, String text, long time) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray oldRows = read(context);
        JSONArray newRows = new JSONArray();
        try {
            JSONObject row = new JSONObject();
            row.put("package", packageName);
            row.put("title", title);
            row.put("text", text);
            row.put("time", time);
            newRows.put(row);
            for (int i = 0; i < oldRows.length() && i < MAX_ROWS - 1; i++) {
                JSONObject oldRow = oldRows.optJSONObject(i);
                if (oldRow != null) newRows.put(oldRow);
            }
            // If encryption fails, do not fall back to storing notification text in cleartext.
            prefs.edit().putString(KEY_ROWS_ENCRYPTED, encrypt(newRows.toString()))
                    .remove(KEY_ROWS).apply();
        } catch (Exception ignored) {
            // Fail closed: the notification is not persisted if secure storage fails.
        }
    }

    public static synchronized void recordListenerState(Context context, boolean connected) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("listener_connected", connected).apply();
    }

    public static synchronized void recordTelegramCallback(Context context, String packageName) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putInt("telegram_callback_count", prefs.getInt("telegram_callback_count", 0) + 1)
                .putString("last_callback_package", packageName)
                .putLong("last_callback_time", System.currentTimeMillis())
                .apply();
    }

    public static synchronized String diagnostics(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long time = p.getLong("last_callback_time", 0L);
        String last = time == 0L ? "هنوز هیچ اعلان تلگرامی به سرویس نرسیده"
                : android.text.format.DateFormat.getTimeFormat(context).format(new java.util.Date(time));
        return "وضعیت دسترسی اعلان: " + (p.getBoolean("listener_connected", false) ? "وصل" : "قطع/نامشخص")
                + "\nتعداد اعلان‌های دریافت‌شده: " + p.getInt("telegram_callback_count", 0)
                + "\nتعداد اعلان‌های ذخیره‌شده: " + read(context).length() + " از " + MAX_ROWS
                + "\nآخرین بسته: " + p.getString("last_callback_package", "—")
                + "\nزمان آخرین دریافت: " + last + "\n\n";
    }

    public static synchronized JSONArray read(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String encrypted = prefs.getString(KEY_ROWS_ENCRYPTED, null);
        if (encrypted != null) {
            try {
                return new JSONArray(decrypt(encrypted));
            } catch (Exception e) {
                // Keep unreadable ciphertext in place for diagnosis; never expose it as plaintext.
                return new JSONArray();
            }
        }

        // One-time migration from the earlier unencrypted local format.
        String legacy = prefs.getString(KEY_ROWS, null);
        if (legacy != null) {
            try {
                JSONArray rows = new JSONArray(legacy);
                String protectedRows = encrypt(rows.toString());
                prefs.edit().putString(KEY_ROWS_ENCRYPTED, protectedRows).remove(KEY_ROWS).apply();
                return rows;
            } catch (Exception e) {
                return new JSONArray();
            }
        }
        return new JSONArray();
    }

    public static synchronized void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_ROWS_ENCRYPTED).remove(KEY_ROWS).apply();
    }

    private static SecretKey getKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getKey());
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] joined = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, joined, 0, iv.length);
        System.arraycopy(ciphertext, 0, joined, iv.length, ciphertext.length);
        return Base64.encodeToString(joined, Base64.NO_WRAP);
    }

    private static String decrypt(String value) throws Exception {
        byte[] joined = Base64.decode(value, Base64.NO_WRAP);
        if (joined.length <= 12) throw new IllegalArgumentException("Encrypted notification data is incomplete");
        byte[] iv = new byte[12];
        System.arraycopy(joined, 0, iv, 0, iv.length);
        byte[] ciphertext = new byte[joined.length - iv.length];
        System.arraycopy(joined, iv.length, ciphertext, 0, ciphertext.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }
}
