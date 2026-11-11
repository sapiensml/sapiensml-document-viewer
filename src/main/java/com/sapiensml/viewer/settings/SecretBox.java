package com.sapiensml.viewer.settings;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-GCM for the secrets in the settings file: {@code v1:} + base64(12-byte IV + ciphertext + tag). */
final class SecretBox {

    private static final String PREFIX = "v1:";

    private static final int IV_BYTES = 12;

    private static final int TAG_BITS = 128;

    /** Binds every secret to this file's purpose */
    private static final byte[] CONTEXT = "sapiensml-document-viewer/alfresco-settings".getBytes(StandardCharsets.UTF_8);

    private final SecretKey key;

    private final SecureRandom random = new SecureRandom();

    SecretBox(byte[] key) {
        if (key.length != 32) {
            throw new IllegalArgumentException("the settings key must be 32 bytes (base64 of 32 random bytes)");
        }
        this.key = new SecretKeySpec(key, "AES");
    }

    static byte[] newKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    String seal(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            this.random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, this.key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(CONTEXT);
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Encrypting a setting failed", ex);
        }
    }

    /** @throws IllegalStateException when the value was sealed with another key or has been altered */
    String open(String sealed) {
        if (sealed == null) {
            return null;
        }
        if (!sealed.startsWith(PREFIX)) {
            throw new IllegalStateException("Unknown secret format");
        }
        try {
            byte[] data = Base64.getDecoder().decode(sealed.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, this.key, new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
            cipher.updateAAD(CONTEXT);
            return new String(cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES), StandardCharsets.UTF_8);
        }
        catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("A stored secret cannot be decrypted (was the settings key changed?)", ex);
        }
    }

}
