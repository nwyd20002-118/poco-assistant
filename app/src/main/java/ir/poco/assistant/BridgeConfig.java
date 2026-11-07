package ir.poco.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores the bridge URL and device token encrypted with an Android Keystore key. */
public final class BridgeConfig {
    private static final String PREFS = "poco_bridge_secure";
    private static final String KEY_ALIAS = "poco_bridge_aes_key";
    private BridgeConfig() {}

    public static synchronized void save(Context context, String endpoint, String token) throws Exception {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putString("endpoint", encrypt(endpoint)).putString("token", encrypt(token)).apply();
    }

    public static synchronized String endpoint(Context context) {
        return decryptQuiet(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("endpoint", null));
    }

    public static synchronized String token(Context context) {
        return decryptQuiet(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("token", null));
    }

    public static synchronized void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private static String decryptQuiet(String value) {
        if (value == null) return "";
        try { return decrypt(value); } catch (Exception e) { return ""; }
    }

    private static SecretKey getKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
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
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] joined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, joined, 0, iv.length);
        System.arraycopy(encrypted, 0, joined, iv.length, encrypted.length);
        return Base64.encodeToString(joined, Base64.NO_WRAP);
    }

    private static String decrypt(String value) throws Exception {
        byte[] joined = Base64.decode(value, Base64.NO_WRAP);
        byte[] iv = new byte[12];
        System.arraycopy(joined, 0, iv, 0, iv.length);
        byte[] encrypted = new byte[joined.length - iv.length];
        System.arraycopy(joined, iv.length, encrypted, 0, encrypted.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }
}
