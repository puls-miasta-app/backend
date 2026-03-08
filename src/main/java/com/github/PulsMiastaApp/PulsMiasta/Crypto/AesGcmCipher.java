package com.github.PulsMiastaApp.PulsMiasta.Crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.SecureRandom;

public class AesGcmCipher {

    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    public static Encrypted encrypt(byte[] data, SecretKey key) throws Exception {
        byte[] iv = new byte[IV_LEN];
        SecureRandom.getInstanceStrong().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));


        return new Encrypted(cipher.doFinal(data), iv);
    }

    public static Encrypted encrypt(byte[] data, SecretKey key, byte[] aad) throws Exception {
        byte[] iv = new byte[12];
        SecureRandom random = new SecureRandom();
        random.nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, spec);
        cipher.updateAAD(aad); // tu jest AAD
        byte[] encrypted = cipher.doFinal(data);

        return new Encrypted(encrypted, iv);
    }

    public static byte[] decrypt(byte[] cipherText, byte[] iv, SecretKey key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        return cipher.doFinal(cipherText);
    }

    public static byte[] decrypt(byte[] cipherText, SecretKey key, byte[] iv, byte[] aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.DECRYPT_MODE, key, spec);
        cipher.updateAAD(aad);
        return cipher.doFinal(cipherText);
    }


    public static StreamEncrypted encryptCipher(SecretKey key, byte[] aad) throws Exception {
        byte[] iv = new byte[IV_LEN];
        SecureRandom.getInstanceStrong().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        if (aad != null) cipher.updateAAD(aad);

        return new StreamEncrypted(cipher, iv);
    }

    public static Cipher decryptCipher(SecretKey key, byte[] iv, byte[] aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.DECRYPT_MODE, key, spec);
        if (aad != null) {
            cipher.updateAAD(aad);
        }
        return cipher;
    }


    public record Encrypted(byte[] data, byte[] iv) {
    }

    public record StreamEncrypted(Cipher cipher, byte[] iv) {
    }
}
