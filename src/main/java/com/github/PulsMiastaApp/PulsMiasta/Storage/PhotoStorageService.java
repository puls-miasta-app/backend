package com.github.PulsMiastaApp.PulsMiasta.Storage;

import com.github.PulsMiastaApp.PulsMiasta.Config.StorageProperties;
import com.github.PulsMiastaApp.PulsMiasta.Crypto.FileCryptoService;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Report;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.ReportPhoto;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Repository.ReportPhotoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PhotoStorageService {

    private final S3Client s3Client;
    private final FileCryptoService fileCryptoService;
    private final StorageProperties storageProperties;
    private final ReportPhotoRepository reportPhotoRepository;

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/heic", "image/heif"
    );

    /**
     * Validate already-read bytes against the declared content type:
     * size, MIME whitelist, and magic-byte sniffing for defense-in-depth.
     */
    private void validateRawBytes(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) {
            throw new StorageException("File is empty");
        }
        if (bytes.length > storageProperties.getMaxFileSize()) {
            throw new StorageException("File exceeds maximum size of %d MB"
                    .formatted(storageProperties.getMaxFileSize() / (1024 * 1024)));
        }
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new StorageException("Unsupported file type");
        }
        byte[] head = new byte[Math.min(12, bytes.length)];
        System.arraycopy(bytes, 0, head, 0, head.length);
        validateMagicBytes(head, contentType.toLowerCase());
    }

    /**
     * Defense-in-depth: verify the file's leading bytes against its declared MIME type.
     * Prevents clients from uploading arbitrary files with a spoofed Content-Type header.
     */
    private void validateMagicBytes(byte[] head, String contentType) {
        if (head.length < 4) {
            throw new StorageException("File too small to validate");
        }

        boolean valid = switch (contentType) {
            case "image/jpeg" -> head[0] == (byte) 0xFF && head[1] == (byte) 0xD8 && head[2] == (byte) 0xFF;
            case "image/png" -> head[0] == (byte) 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G';
            case "image/webp" -> head.length >= 12
                    && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
            case "image/heic", "image/heif" -> head.length >= 12
                    && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p';
            default -> false;
        };

        if (!valid) {
            throw new StorageException("File content does not match declared type");
        }
    }

    /**
     * Stream a decrypted object from R2 straight to the given output (typically the
     * HTTP response body). Uses true streaming — the encrypted blob is pulled from
     * R2 in chunks, passed through the AES-GCM decryptor, and written to {@code out}
     * without ever materialising the full file in memory.
     */
    public void streamDecrypted(String objectKey, OutputStream out) {
        try (ResponseInputStream<GetObjectResponse> encrypted = s3Client.getObject(
                GetObjectRequest.builder()
                        .bucket(storageProperties.getBucket())
                        .key(objectKey)
                        .build())) {
            fileCryptoService.decrypt(encrypted, out);
        } catch (NoSuchKeyException e) {
            throw new StorageException("Photo object not found: " + objectKey);
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("Failed to download or decrypt photo", e);
        }
    }

    public void deleteObject(String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(storageProperties.getBucket())
                    .key(key)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to delete orphaned object: key={}", key, e);
        }
    }

    /**
     * Upload a photo whose raw bytes have already been read from the request.
     * Prefer this overload when the caller needs the same bytes for another
     * purpose (e.g. async AI analysis) — it avoids calling
     * {@link MultipartFile#getInputStream()} a second time.
     */
    public ReportPhoto uploadAndSavePhoto(
            byte[] rawBytes,
            String originalFilename,
            String contentType,
            User user,
            Report report) {

        validateRawBytes(rawBytes, contentType);

        String objectKey = buildObjectKey(user.getId(), originalFilename);
        long rawSize = rawBytes.length;

        try {
            ByteArrayOutputStream encryptedBuffer = new ByteArrayOutputStream();
            try (ByteArrayInputStream raw = new ByteArrayInputStream(rawBytes)) {
                fileCryptoService.encrypt(raw, encryptedBuffer);
            }

            byte[] encryptedBytes = encryptedBuffer.toByteArray();

            if (encryptedBytes.length >= storageProperties.getMultipartThreshold()) {
                multipartUpload(objectKey, encryptedBytes);
            } else {
                singleUpload(objectKey, encryptedBytes);
            }

            ReportPhoto photo = new ReportPhoto();
            photo.setUser(user);
            photo.setReport(report);
            photo.setObjectKey(objectKey);
            photo.setOriginalFilename(originalFilename);
            photo.setContentType(contentType);
            photo.setFileSize(rawSize);
            reportPhotoRepository.save(photo);

            log.info("Photo uploaded: id={}, key={}, size={}, encrypted={}",
                    photo.getId(), objectKey, rawSize, encryptedBytes.length);
            return photo;

        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("Failed to encrypt and upload photo", e);
        }
    }

    private void singleUpload(String key, byte[] data) {
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(storageProperties.getBucket())
                        .key(key)
                        .contentType("application/octet-stream")
                        .build(),
                RequestBody.fromBytes(data)
        );
    }

    private void multipartUpload(String key, byte[] data) {
        String uploadId = s3Client.createMultipartUpload(
                CreateMultipartUploadRequest.builder()
                        .bucket(storageProperties.getBucket())
                        .key(key)
                        .contentType("application/octet-stream")
                        .build()
        ).uploadId();

        List<CompletedPart> completedParts = new ArrayList<>();
        int partSize = (int) storageProperties.getMultipartPartSize();
        int partNumber = 1;

        try {
            for (int offset = 0; offset < data.length; offset += partSize) {
                int length = Math.min(partSize, data.length - offset);

                byte[] partData = Arrays.copyOfRange(data, offset, offset + length);

                UploadPartResponse partResponse = s3Client.uploadPart(
                        UploadPartRequest.builder()
                                .bucket(storageProperties.getBucket())
                                .key(key)
                                .uploadId(uploadId)
                                .partNumber(partNumber)
                                .contentLength((long) length)
                                .build(),
                        RequestBody.fromBytes(partData)
                );

                completedParts.add(CompletedPart.builder()
                        .partNumber(partNumber)
                        .eTag(partResponse.eTag())
                        .build());

                partNumber++;
            }

            s3Client.completeMultipartUpload(
                    CompleteMultipartUploadRequest.builder()
                            .bucket(storageProperties.getBucket())
                            .key(key)
                            .uploadId(uploadId)
                            .multipartUpload(CompletedMultipartUpload.builder()
                                    .parts(completedParts)
                                    .build())
                            .build()
            );

        } catch (Exception e) {
            abortMultipartUpload(key, uploadId);
            throw new StorageException("Multipart upload failed for key: " + key, e);
        }
    }

    private void abortMultipartUpload(String key, String uploadId) {
        try {
            s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(storageProperties.getBucket())
                    .key(key)
                    .uploadId(uploadId)
                    .build());
        } catch (Exception ex) {
            log.warn("Failed to abort multipart upload: key={}, uploadId={}", key, uploadId, ex);
        }
    }

    private String buildObjectKey(Long userId, String originalFilename) {
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf('.'));
        }
        return "photos/%d/%s%s.enc".formatted(userId, UUID.randomUUID(), extension);
    }
}
