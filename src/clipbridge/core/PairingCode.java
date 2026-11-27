package clipbridge.core;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * The pairing code is the shared secret. It has 16 characters from a 32-letter alphabet
 * (80 bits) and is turned into an AES-256 key with PBKDF2.
 */
public final class PairingCode {
    /** No I, O, 0 or 1, so a code that is read out loud or typed by hand is hard to get wrong. */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public static final int LENGTH = 16;
    private static final int ITERATIONS = 120_000;
    private static final byte[] SALT = "ClipBridge pairing code v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    private PairingCode() {
    }

    public static String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Upper-cases the text and removes spaces and dashes, so "abcd-efgh ..." can be typed freely. */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c == ' ' || c == '-' || c == '\t' || c == '\n' || c == '\r') {
                continue;
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    public static boolean isValid(String text) {
        String n = normalize(text);
        if (n.length() != LENGTH) {
            return false;
        }
        for (char c : n.toCharArray()) {
            if (ALPHABET.indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    /** XXXX-XXXX-XXXX-XXXX */
    public static String format(String text) {
        String n = normalize(text);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                sb.append('-');
            }
            sb.append(n.charAt(i));
        }
        return sb.toString();
    }

    /** Explains in plain words why a code is not accepted, or returns null when it is fine. */
    public static String explainProblem(String text) {
        String n = normalize(text);
        if (n.isEmpty()) {
            return "Enter a pairing code, or click Generate new.";
        }
        if (n.length() != LENGTH) {
            return "A pairing code has " + LENGTH + " characters. You typed " + n.length() + ".";
        }
        for (char c : n.toCharArray()) {
            if (ALPHABET.indexOf(c) < 0) {
                return "The character '" + c + "' is not used in pairing codes (no I, O, 0 or 1).";
            }
        }
        return null;
    }

    public static byte[] deriveKey(String code) {
        try {
            PBEKeySpec spec = new PBEKeySpec(normalize(code).toCharArray(), SALT, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("This Java installation cannot derive keys: " + e.getMessage(), e);
        }
    }
}
