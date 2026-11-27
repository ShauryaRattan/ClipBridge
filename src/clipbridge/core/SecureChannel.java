package clipbridge.core;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * One direction-aware AES-256-GCM channel.
 *
 * The 12-byte IV is built from a direction number and a message counter that both sides count
 * themselves; the counter is never sent. So a message that is replayed, reordered, dropped or
 * changed no longer decrypts, and the connection is closed.
 */
public final class SecureChannel {
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final int sendDirection;
    private final int receiveDirection;
    private long sendCounter;
    private long receiveCounter;

    public SecureChannel(byte[] sessionKey, boolean initiator) {
        this.key = new SecretKeySpec(sessionKey, "AES");
        this.sendDirection = initiator ? 1 : 2;
        this.receiveDirection = initiator ? 2 : 1;
    }

    /** A fresh key for every connection, so two connections never share a key stream. */
    public static byte[] deriveSession(byte[] baseKey, byte[] initiatorNonce, byte[] acceptorNonce)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(baseKey, "HmacSHA256"));
        mac.update("ClipBridge session".getBytes(StandardCharsets.UTF_8));
        mac.update(initiatorNonce);
        mac.update(acceptorNonce);
        return mac.doFinal();
    }

    public synchronized byte[] seal(byte[] plain) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv(sendDirection, sendCounter)));
        byte[] out = cipher.doFinal(plain);
        sendCounter++;
        return out;
    }

    /** Throws if the data is not exactly the next message from the other side. */
    public synchronized byte[] open(byte[] sealed) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv(receiveDirection, receiveCounter)));
        byte[] out = cipher.doFinal(sealed);
        receiveCounter++;
        return out;
    }

    private static byte[] iv(int direction, long counter) {
        return ByteBuffer.allocate(12).putInt(direction).putLong(counter).array();
    }
}
