package com.github.PulsMiastaApp.PulsMiasta.Crypto;

import javax.crypto.Cipher;

public record StreamEncryptedData(String kekName, byte[] encryptedDek, byte[] dekIv, byte[] dataIv, Cipher dataCipher) {
}
