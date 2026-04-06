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
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        SecretKey dek = keyGen.generateKey();

        String kekName = keyProvider.getActiveKeyName();
        byte[] aad = kekName.getBytes(StandardCharsets.UTF_8);

        var dataEnc = AesGcmCipher.encrypt(data, dek, aad);

        SecretKey kek = keyProvider.getActiveKey();
        if (kek == null) {
            throw new EncryptionException("Active KEK not found");
        }
        var dekEnc = AesGcmCipher.encrypt(dek.getEncoded(), kek, aad);

        return new EncryptedData(
                dataEnc.data(),
                dekEnc.data(),
                dekEnc.iv(),
                dataEnc.iv(),
                kekName
        );
    }


    public byte[] decrypt(EncryptedData encryptedData) throws Exception {
        SecretKey kek = keyProvider.getKey(encryptedData.kekName());
        if (kek == null) {
            throw new EncryptionException("KEK not found: " + encryptedData.kekName());
        }

        byte[] aad = encryptedData.kekName().getBytes(StandardCharsets.UTF_8);

        byte[] dekBytes = AesGcmCipher.decrypt(
                encryptedData.encryptedDek(),
                kek,
                encryptedData.dekIv(),
                aad
        );

        SecretKey dek = new SecretKeySpec(dekBytes, "AES");

        return AesGcmCipher.decrypt(
                encryptedData.ciphertext(),
                dek,
                encryptedData.iv(),
                aad
        );
    }


    public StreamEncryptedData prepareStreamEncryption() throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        SecretKey dek = keyGen.generateKey();

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
