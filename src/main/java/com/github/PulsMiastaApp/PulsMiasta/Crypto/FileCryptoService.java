package com.github.PulsMiastaApp.PulsMiasta.Crypto;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

@RequiredArgsConstructor
@Service
public class FileCryptoService {

    private final CryptoService cryptoService;

    private static final int MAGIC = 0x4D414731; // np. "MAG1"

    public void encrypt(InputStream in, OutputStream out) throws Exception {
        StreamEncryptedData meta = cryptoService.prepareStreamEncryption();
        DataOutputStream dos = new DataOutputStream(out);

        byte[] kekNameBytes = meta.kekName().getBytes(StandardCharsets.UTF_8);
        byte[] dekIv = meta.dekIv();
        byte[] encryptedDek = meta.encryptedDek();
        byte[] dataIv = meta.dataIv();

        if (kekNameBytes.length > 255) throw new IllegalArgumentException("kekName too long");
        if (dekIv.length > 255) throw new IllegalArgumentException("dekIv too long");
        if (dataIv.length > 255) throw new IllegalArgumentException("dataIv too long");


        dos.writeInt(MAGIC);
        dos.writeByte(kekNameBytes.length);
        dos.write(kekNameBytes);
        dos.writeByte(dekIv.length);
        dos.write(dekIv);
        dos.writeShort(encryptedDek.length);
        dos.write(encryptedDek);
        dos.writeByte(dataIv.length);
        dos.write(dataIv);

        try (CipherOutputStream cos = new CipherOutputStream(dos, meta.dataCipher())) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Encryption interrupted during data transfer");
                }
                cos.write(buffer, 0, read);
            }
            cos.flush();
        }
    }

    public void decrypt(InputStream in, OutputStream out) throws Exception {
        DataInputStream dis = new DataInputStream(in);
        if (dis.readInt() != 0x4D414731) {
            throw new IllegalArgumentException("Invalid file format");
        }
        byte[] kekName = dis.readNBytes(dis.readByte());
        byte[] dekIv = dis.readNBytes(dis.readByte());
        byte[] encryptedDek = dis.readNBytes(dis.readShort());
        byte[] dataIv = dis.readNBytes(dis.readByte());
        Cipher cipher = cryptoService.prepareStreamDecryption(
                new String(kekName, StandardCharsets.UTF_8),
                encryptedDek,
                dekIv,
                dataIv);
        try (CipherInputStream cis = new CipherInputStream(dis, cipher)) {
            cis.transferTo(out);
        }
    }
}
