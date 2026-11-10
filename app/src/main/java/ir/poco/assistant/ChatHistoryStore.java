package ir.poco.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores the local chat transcript encrypted with an Android Keystore AES-GCM key.
 * The previous plaintext SharedPreferences entry is migrated once, then removed.
 */
public final class ChatHistoryStore {
    private static final String SECURE_PREFS = "poco_chat_secure";
    private static final String ENCRYPTED_HISTORY = "messages_encrypted";
    private static final String LEGACY_PREFS = "poco_chat";
    private static final String LEGACY_HISTORY = "messages";
    private static final String KEY_ALIAS = "poco_chat_history_aes_key";
    private static final int IV_LENGTH = 12;
    private static final int MAX_MESSAGES = 100;

    private ChatHistoryStore() {}

    public static synchronized JSONArray load(Context context) {
        SharedPreferences secure = context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE);
        String encrypted = secure.getString(ENCRYPTED_HISTORY, null);
        if (encrypted != null) {
            try {
                return new JSONArray(decrypt(encrypted));
            } catch (Exception e) {
                // Fail closed: never interpret unreadable ciphertext as plaintext.
                return new JSONArray();
            }
        }

        SharedPreferences legacyPrefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE);
        String legacy = legacyPrefs.getString(LEGACY_HISTORY, null);
        if (legacy == null) return new JSONArray();

        try {
            JSONArray oldHistory = new JSONArray(legacy);
            if (save(context, oldHistory)) return oldHistory;
        } catch (Exception ignored) {
            // Invalid legacy history is discarded below.
        }

        // Do not leave the previous plaintext transcript behind if migration fails.
        legacyPrefs.edit().remove(LEGACY_HISTORY).apply();
        return new JSONArray();
    }

    public static synchronized boolean save(Context context, JSONArray history) {
        try {
            JSONArray limited = new JSONArray();
            int start = Math.max(0, history.length() - MAX_MESSAGES);
            for (int i = start; i < history.length(); i++) {
                JSONObject item = history.optJSONObject(i);
                if (item != null) limited.put(item);
            }

            String encrypted = encrypt(limited.toString());
            context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE).edit()
                    .putString(ENCRYPTED_HISTORY, encrypted)
                    .apply();
            context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).edit()
                    .remove(LEGACY_HISTORY)
                    .apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static synchronized void clear(Context context) {
        context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE).edit()
                .remove(ENCRYPTED_HISTORY).apply();
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).edit()
                .remove(LEGACY_HISTORY).apply();
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

    private static String encrypt(String plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getKey());
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
        return Base64.encodeToString(combined, Base64.NO_WRAP);
    }

    private static String decrypt(String encoded) throws Exception {
        byte[] combined = Base64.decode(encoded, Base64.NO_WRAP);
        if (combined.length <= IV_LENGTH) throw new IllegalArgumentException("Encrypted chat history is incomplete");

        byte[] iv = new byte[IV_LENGTH];
        byte[] ciphertext = new byte[combined.length - IV_LENGTH];
        System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
        System.arraycopy(combined, IV_LENGTH, ciphertext, 0, ciphertext.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }
}
