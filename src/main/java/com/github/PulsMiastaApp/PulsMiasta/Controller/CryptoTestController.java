package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.SuccessResponse;
import com.github.PulsMiastaApp.PulsMiasta.Crypto.CryptoService;
import com.github.PulsMiastaApp.PulsMiasta.Crypto.EncryptedData;
import com.github.PulsMiastaApp.PulsMiasta.Crypto.FileCryptoService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

@Profile("test")
@RestController
@RequestMapping("/v1/test/crypto")
@RequiredArgsConstructor
public class CryptoTestController {

    private final CryptoService cryptoService;
    private final FileCryptoService fileCryptoService;

    @PostMapping("/encrypt")
    public ResponseEntity<SuccessResponse<Map<String, String>>> encrypt(@RequestBody Map<String, String> body) throws Exception {
        String plaintext = body.get("plaintext");
        if (plaintext == null || plaintext.isBlank()) {
            return ResponseEntity.badRequest().body(SuccessResponse.of(Map.of("error", "plaintext is required")));
        }

        EncryptedData encrypted = cryptoService.encrypt(plaintext.getBytes(StandardCharsets.UTF_8));

        return ResponseEntity.ok(SuccessResponse.of(Map.of(
                "ciphertext", Base64.getEncoder().encodeToString(encrypted.ciphertext()),
                "encryptedDek", Base64.getEncoder().encodeToString(encrypted.encryptedDek()),
                "dekIv", Base64.getEncoder().encodeToString(encrypted.dekIv()),
                "iv", Base64.getEncoder().encodeToString(encrypted.iv()),
                "kekName", encrypted.kekName()
        )));
    }

    @PostMapping("/decrypt")
    public ResponseEntity<SuccessResponse<Map<String, String>>> decrypt(@RequestBody Map<String, String> body) throws Exception {
        EncryptedData encrypted = new EncryptedData(
                Base64.getDecoder().decode(body.get("ciphertext")),
                Base64.getDecoder().decode(body.get("encryptedDek")),
                Base64.getDecoder().decode(body.get("dekIv")),
                Base64.getDecoder().decode(body.get("iv")),
                body.get("kekName")
        );

        byte[] decrypted = cryptoService.decrypt(encrypted);

        return ResponseEntity.ok(SuccessResponse.of(Map.of(
                "plaintext", new String(decrypted, StandardCharsets.UTF_8)
        )));
    }

    @PostMapping("/round-trip")
    public ResponseEntity<SuccessResponse<Map<String, Object>>> roundTrip(@RequestBody Map<String, String> body) throws Exception {
        String plaintext = body.get("plaintext");
        if (plaintext == null || plaintext.isBlank()) {
            return ResponseEntity.badRequest().body(SuccessResponse.of(Map.of()));
        }

        byte[] original = plaintext.getBytes(StandardCharsets.UTF_8);
        EncryptedData encrypted = cryptoService.encrypt(original);
        byte[] decrypted = cryptoService.decrypt(encrypted);

        boolean match = Arrays.equals(original, decrypted);

        return ResponseEntity.ok(SuccessResponse.of(Map.of(
                "originalText", plaintext,
                "decryptedText", new String(decrypted, StandardCharsets.UTF_8),
                "match", match,
                "kekName", encrypted.kekName(),
                "ciphertextLength", encrypted.ciphertext().length
        )));
    }

    @PostMapping(value = "/file/encrypt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> encryptFile(@RequestParam("file") MultipartFile file) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        fileCryptoService.encrypt(file.getInputStream(), out);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"" + file.getOriginalFilename() + ".enc\"")
                .body(out.toByteArray());
    }

    @PostMapping(value = "/file/decrypt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> decryptFile(@RequestParam("file") MultipartFile file) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        fileCryptoService.decrypt(file.getInputStream(), out);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"decrypted\"")
                .body(out.toByteArray());
    }

    @PostMapping("/file/round-trip")
    public ResponseEntity<SuccessResponse<Map<String, Object>>> fileRoundTrip(@RequestBody Map<String, String> body) throws Exception {
        String plaintext = body.get("plaintext");
        if (plaintext == null || plaintext.isBlank()) {
            return ResponseEntity.badRequest().body(SuccessResponse.of(Map.of()));
        }

        byte[] original = plaintext.getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream encOut = new ByteArrayOutputStream();
        fileCryptoService.encrypt(new ByteArrayInputStream(original), encOut);

        ByteArrayOutputStream decOut = new ByteArrayOutputStream();
        fileCryptoService.decrypt(new ByteArrayInputStream(encOut.toByteArray()), decOut);

        byte[] decrypted = decOut.toByteArray();
        boolean match = Arrays.equals(original, decrypted);

        return ResponseEntity.ok(SuccessResponse.of(Map.of(
                "originalText", plaintext,
                "decryptedText", new String(decrypted, StandardCharsets.UTF_8),
                "match", match,
                "encryptedSize", encOut.size()
        )));
    }
}
