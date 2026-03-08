package com.github.PulsMiastaApp.PulsMiasta.Crypto;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class CryptoService {

    private final CryptoKeyProvider keyProvider;

    public EncryptedData encrypt(byte[] data) throws Exception {
        SecretKey dek = KeyGenerator.getInstance("AES").generateKey();

        var dataEnc = AesGcmCipher.encrypt(data, dek);

        SecretKey kek = keyProvider.getActiveKey();
        if (kek == null) {
            throw new EncryptionException("Active KEK not found");
        }
        var dekEnc = AesGcmCipher.encrypt(dek.getEncoded(), kek);

        return new EncryptedData(
                dataEnc.data(),
                dekEnc.data(),
                dekEnc.iv(),
                dataEnc.iv(),
                keyProvider.getActiveKeyName()
        );
    }


    public byte[] decrypt(EncryptedData encryptedData) throws Exception {
        SecretKey kek = keyProvider.getKey(encryptedData.kekName());
        if (kek == null) {
            throw new EncryptionException("KEK not found: " + encryptedData.kekName());
        }

        byte[] dekBytes = AesGcmCipher.decrypt(
                encryptedData.encryptedDek(),
                encryptedData.dekIv(),
                kek
        );

        SecretKey dek = new SecretKeySpec(dekBytes, "AES");

        return AesGcmCipher.decrypt(
                encryptedData.ciphertext(),
                encryptedData.iv(),
                dek
        );
    }


    public StreamEncryptedData prepareStreamEncryption() throws Exception {
        SecretKey dek = KeyGenerator.getInstance("AES").generateKey();

        byte[] aad = keyProvider.getActiveKeyName().getBytes(StandardCharsets.UTF_8);

        var dataStream = AesGcmCipher.encryptCipher(dek, aad);

        SecretKey kek = keyProvider.getActiveKey();
        if (kek == null) {
            throw new EncryptionException("Active KEK not found");
        }
        var dekEnc = AesGcmCipher.encrypt(dek.getEncoded(), kek, aad);

        return new StreamEncryptedData(
                keyProvider.getActiveKeyName(),
                dekEnc.data(),
                dekEnc.iv(),
                dataStream.iv(),
                dataStream.cipher()
        );
    }

    public Cipher prepareStreamDecryption(
            String kekName,
            byte[] encryptedDek,
            byte[] dekIv,
            byte[] dataIv
    ) throws Exception {

        SecretKey kek = keyProvider.getKey(kekName);
        if (kek == null) throw new EncryptionException("KEK not found: " + kekName);

        byte[] aad = kekName.getBytes(StandardCharsets.UTF_8);

        // odszyfrowanie DEK z użyciem AAD
        byte[] dekBytes = AesGcmCipher.decrypt(encryptedDek, kek, dekIv, aad);
        SecretKey dek = new SecretKeySpec(dekBytes, "AES");

        // przygotowanie Cipher dla danych z AAD
        return AesGcmCipher.decryptCipher(dek, dataIv, aad);
    }


}
