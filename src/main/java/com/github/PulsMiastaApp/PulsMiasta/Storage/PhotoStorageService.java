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

    public void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new StorageException("File is empty");
        }
        if (file.getSize() > storageProperties.getMaxFileSize()) {
            throw new StorageException("File exceeds maximum size of %d MB"
                    .formatted(storageProperties.getMaxFileSize() / (1024 * 1024)));
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new StorageException("Unsupported file type: " + contentType
                    + ". Allowed: " + String.join(", ", ALLOWED_CONTENT_TYPES));
        }
    }

    public ReportPhoto uploadAndSavePhoto(MultipartFile file, User user, Report report) {
        validateFile(file);

        String objectKey = buildObjectKey(user.getId(), file.getOriginalFilename());

        try {
            ByteArrayOutputStream encryptedBuffer = new ByteArrayOutputStream();
            try (InputStream raw = file.getInputStream()) {
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
            photo.setOriginalFilename(file.getOriginalFilename());
            photo.setContentType(file.getContentType());
            photo.setFileSize(file.getSize());
            reportPhotoRepository.save(photo);

            log.info("Photo uploaded: id={}, key={}, size={}, encrypted={}",
                    photo.getId(), objectKey, file.getSize(), encryptedBytes.length);
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
