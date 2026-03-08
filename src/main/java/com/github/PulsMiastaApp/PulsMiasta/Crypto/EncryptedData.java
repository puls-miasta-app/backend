package com.github.PulsMiastaApp.PulsMiasta.Crypto;

public record EncryptedData(byte[] ciphertext, byte[] encryptedDek, byte[] dekIv, byte[] iv, String kekName) {
}
