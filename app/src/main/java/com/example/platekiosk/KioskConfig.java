package com.example.platekiosk;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class KioskConfig {
    private static final String PREFERENCES = "kiosk_config";
    private static final String KEY_URL = "url";
    private static final String KEY_KIOSK_ACTIVE = "kiosk_active";
    private static final String KEY_PIN_HASH = "pin_hash";
    private static final String KEY_PIN_SALT = "pin_salt";
    private static final String KEY_PIN_CIPHERTEXT = "pin_ciphertext";
    private static final String KEY_PIN_IV = "pin_iv";
    private static final String KEY_DEFAULT_PIN_MIGRATED = "default_pin_migrated_to_4711";
    private static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";
    private static final String KEY_THIRD_PARTY_COOKIES = "third_party_cookies";
    private static final String KEY_RELOAD_ON_ERROR = "reload_on_error";
    private static final String KEY_RELOAD_ON_RECONNECT = "reload_on_reconnect";
    private static final String KEY_ERROR_RELOAD_DELAY_SECONDS = "error_reload_delay_seconds";
    private static final String KEY_AUTO_RELOAD_MINUTES = "auto_reload_minutes";
    private static final String KEY_GOOGLE_DEFAULT_MIGRATED = "default_url_migrated_to_google";
    private static final String DEFAULT_URL = "https://www.google.com";
    private static final String LEGACY_DEFAULT_URL = "https://example.com";
    private static final String PREVIOUS_CLIFTON_DEFAULT_URL =
            "https://smartvalidation-cliftondownsc.prod.parking-core.scheidt-bachmann.net/"
                    + "validation-kiosk-app/validation/main.jsf";
    private static final String DEFAULT_PIN = "4711";
    private static final String LEGACY_DEFAULT_PIN = "1234";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String PIN_KEY_ALIAS = "plate_kiosk_pin_key";

    private KioskConfig() {
    }

    public static String getUrl(Context context) {
        SharedPreferences preferences = preferences(context);
        String url = preferences.getString(KEY_URL, DEFAULT_URL);
        if (!preferences.getBoolean(KEY_GOOGLE_DEFAULT_MIGRATED, false)) {
            if (LEGACY_DEFAULT_URL.equals(url) || PREVIOUS_CLIFTON_DEFAULT_URL.equals(url)) {
                url = DEFAULT_URL;
                preferences.edit().putString(KEY_URL, url).apply();
            }
            preferences.edit().putBoolean(KEY_GOOGLE_DEFAULT_MIGRATED, true).apply();
        }
        return url;
    }

    public static void setUrl(Context context, String url) {
        preferences(context).edit().putString(KEY_URL, url).apply();
    }

    public static boolean isKioskActive(Context context) {
        return preferences(context).getBoolean(KEY_KIOSK_ACTIVE, false);
    }

    public static void setKioskActive(Context context, boolean active) {
        preferences(context).edit().putBoolean(KEY_KIOSK_ACTIVE, active).apply();
    }

    public static boolean keepScreenOn(Context context) {
        return preferences(context).getBoolean(KEY_KEEP_SCREEN_ON, true);
    }

    public static void setKeepScreenOn(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply();
    }

    public static boolean allowThirdPartyCookies(Context context) {
        return preferences(context).getBoolean(KEY_THIRD_PARTY_COOKIES, true);
    }

    public static void setAllowThirdPartyCookies(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(KEY_THIRD_PARTY_COOKIES, enabled).apply();
    }

    public static boolean reloadOnError(Context context) {
        return preferences(context).getBoolean(KEY_RELOAD_ON_ERROR, true);
    }

    public static void setReloadOnError(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(KEY_RELOAD_ON_ERROR, enabled).apply();
    }

    public static boolean reloadOnReconnect(Context context) {
        return preferences(context).getBoolean(KEY_RELOAD_ON_RECONNECT, true);
    }

    public static void setReloadOnReconnect(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(KEY_RELOAD_ON_RECONNECT, enabled).apply();
    }

    public static int getErrorReloadDelaySeconds(Context context) {
        return preferences(context).getInt(KEY_ERROR_RELOAD_DELAY_SECONDS, 10);
    }

    public static void setErrorReloadDelaySeconds(Context context, int seconds) {
        preferences(context).edit().putInt(KEY_ERROR_RELOAD_DELAY_SECONDS, seconds).apply();
    }

    public static int getAutoReloadMinutes(Context context) {
        return preferences(context).getInt(KEY_AUTO_RELOAD_MINUTES, 0);
    }

    public static void setAutoReloadMinutes(Context context, int minutes) {
        preferences(context).edit().putInt(KEY_AUTO_RELOAD_MINUTES, minutes).apply();
    }

    public static boolean verifyPin(Context context, String pin) {
        SharedPreferences preferences = preferences(context);
        ensurePin(preferences);
        byte[] expectedHash = Base64.decode(preferences.getString(KEY_PIN_HASH, ""), Base64.NO_WRAP);
        byte[] salt = Base64.decode(preferences.getString(KEY_PIN_SALT, ""), Base64.NO_WRAP);
        return MessageDigest.isEqual(expectedHash, hashPin(pin, salt));
    }

    public static void setPin(Context context, String pin) {
        setPin(preferences(context), pin);
    }

    public static String getPinForSettings(Context context) {
        SharedPreferences preferences = preferences(context);
        ensurePin(preferences);
        String ciphertext = preferences.getString(KEY_PIN_CIPHERTEXT, "");
        String iv = preferences.getString(KEY_PIN_IV, "");
        if (!ciphertext.isEmpty() && !iv.isEmpty()) {
            return decryptPin(ciphertext, iv);
        }

        if (verifyPin(context, DEFAULT_PIN)) {
            setPin(preferences, DEFAULT_PIN);
            return DEFAULT_PIN;
        }
        return "";
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static void ensurePin(SharedPreferences preferences) {
        if (!preferences.contains(KEY_PIN_HASH) || !preferences.contains(KEY_PIN_SALT)) {
            setPin(preferences, DEFAULT_PIN);
        }
        migrateLegacyDefaultPin(preferences);
    }

    private static void migrateLegacyDefaultPin(SharedPreferences preferences) {
        if (preferences.getBoolean(KEY_DEFAULT_PIN_MIGRATED, false)) {
            return;
        }

        boolean usesLegacyDefault = false;
        String ciphertext = preferences.getString(KEY_PIN_CIPHERTEXT, "");
        String iv = preferences.getString(KEY_PIN_IV, "");
        if (!ciphertext.isEmpty() && !iv.isEmpty()) {
            usesLegacyDefault = LEGACY_DEFAULT_PIN.equals(decryptPin(ciphertext, iv));
        } else {
            byte[] expectedHash =
                    Base64.decode(preferences.getString(KEY_PIN_HASH, ""), Base64.NO_WRAP);
            byte[] salt = Base64.decode(preferences.getString(KEY_PIN_SALT, ""), Base64.NO_WRAP);
            usesLegacyDefault =
                    MessageDigest.isEqual(expectedHash, hashPin(LEGACY_DEFAULT_PIN, salt));
        }

        if (usesLegacyDefault) {
            setPin(preferences, DEFAULT_PIN);
        }
        preferences.edit().putBoolean(KEY_DEFAULT_PIN_MIGRATED, true).apply();
    }

    private static void setPin(SharedPreferences preferences, String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        EncryptedPin encryptedPin = encryptPin(pin);
        preferences.edit()
                .putString(KEY_PIN_HASH, Base64.encodeToString(hashPin(pin, salt), Base64.NO_WRAP))
                .putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(KEY_PIN_CIPHERTEXT, encryptedPin.ciphertext)
                .putString(KEY_PIN_IV, encryptedPin.iv)
                .apply();
    }

    private static EncryptedPin encryptPin(String pin) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getPinKey());
            return new EncryptedPin(
                    Base64.encodeToString(
                            cipher.doFinal(pin.getBytes(StandardCharsets.UTF_8)),
                            Base64.NO_WRAP),
                    Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encrypt the kiosk PIN", exception);
        }
    }

    private static String decryptPin(String ciphertext, String iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    getPinKey(),
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(
                    cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)),
                    StandardCharsets.UTF_8);
        } catch (Exception exception) {
            return "";
        }
    }

    private static SecretKey getPinKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(PIN_KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(PIN_KEY_ALIAS, null)).getSecretKey();
        }

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(
                new KeyGenParameterSpec.Builder(
                        PIN_KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build());
        return generator.generateKey();
    }

    private static final class EncryptedPin {
        private final String ciphertext;
        private final String iv;

        private EncryptedPin(String ciphertext, String iv) {
            this.ciphertext = ciphertext;
            this.iv = iv;
        }
    }

    private static byte[] hashPin(String pin, byte[] salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            return digest.digest(pin.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
