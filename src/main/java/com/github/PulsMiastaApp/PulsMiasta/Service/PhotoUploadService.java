package com.github.PulsMiastaApp.PulsMiasta.Service;

import com.github.PulsMiastaApp.PulsMiasta.Crypto.CryptoService;
import com.github.PulsMiastaApp.PulsMiasta.Crypto.StreamEncryptedData;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.TaskEntry;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.TaskStatus;
import com.github.PulsMiastaApp.PulsMiasta.Repository.TaskEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import javax.crypto.CipherOutputStream;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PhotoUploadService {

    /** Minimum part size mandated by the S3 specification (5 MiB). */
    private static final int PART_SIZE = 5 * 1024 * 1024;

    /** Magic bytes matching FileCryptoService — enables decryption via FileCryptoService.decrypt(). */
    private static final int MAGIC = 0x4D414731;

    private final CryptoService cryptoService;
    private final TaskEntryRepository taskEntryRepository;
    private final S3Client s3Client;

    @Value("${r2.bucket}")
    private String bucket;

    /**
     * Processes a photo upload asynchronously.
     *
     * <p>Steps:
     * <ol>
     *   <li>Mark task PROCESSING.</li>
     *   <li>Prepare AES-GCM streaming cipher (DEK wrapped by active KEK).</li>
     *   <li>Write encrypted file (header + ciphertext) to a temp file using the
     *       same binary format as {@code FileCryptoService} so the existing
     *       {@code FileCryptoService.decrypt()} can decrypt R2 objects later.</li>
     *   <li>Multipart-upload the temp file in 5 MiB parts to Cloudflare R2.</li>
     *   <li>Persist the R2 key and data IV to {@code TaskEntry}, mark COMPLETED.</li>
     *   <li>On any failure: abort the in-progress multipart upload (avoids R2
     *       charges for incomplete uploads), mark task FAILED.</li>
     * </ol>
     *
     * @param taskId           UUID of the pre-persisted {@link TaskEntry}.
     * @param inputFile        path to the raw (plaintext) source file; deleted on completion.
     * @param originalFilename original filename from the HTTP multipart request.
     */
    @Async("photoUploadExecutor")
    public void processUpload(UUID taskId, Path inputFile, String originalFilename) {
        TaskEntry task = taskEntryRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("TaskEntry not found: " + taskId));
        task.setStatus(TaskStatus.PROCESSING);
        taskEntryRepository.save(task);

        Path encryptedTemp = null;
        String uploadId = null;
        String r2Key = null;

        try {
            // ── 1. Prepare envelope encryption ──────────────────────────────────
            StreamEncryptedData meta = cryptoService.prepareStreamEncryption();

            // ── 2. Encrypt input file → temp file ───────────────────────────────
            encryptedTemp = Files.createTempFile("pm-enc-" + taskId + "-", ".tmp");
            encryptToTempFile(inputFile, encryptedTemp, meta);

            long encryptedSize = Files.size(encryptedTemp);
            r2Key = buildR2Key(taskId, originalFilename);

            // ── 3. Initiate S3 multipart upload ──────────────────────────────────
            uploadId = s3Client.createMultipartUpload(
                    CreateMultipartUploadRequest.builder()
                            .bucket(bucket)
                            .key(r2Key)
                            .build()
            ).uploadId();

            // ── 4. Upload in 5 MiB parts ─────────────────────────────────────────
            List<CompletedPart> completedParts = uploadParts(encryptedTemp, encryptedSize, r2Key, uploadId);

            s3Client.completeMultipartUpload(
                    CompleteMultipartUploadRequest.builder()
                            .bucket(bucket)
                            .key(r2Key)
                            .uploadId(uploadId)
                            .multipartUpload(CompletedMultipartUpload.builder()
                                    .parts(completedParts)
                                    .build())
                            .build()
            );

            // ── 5. Persist metadata ───────────────────────────────────────────────
            task.setR2Key(r2Key);
            task.setEncryptionIv(Base64.getEncoder().encodeToString(meta.dataIv()));
            task.setStatus(TaskStatus.COMPLETED);
            taskEntryRepository.save(task);

            log.info("Upload completed: taskId={} r2Key={} size={} bytes", taskId, r2Key, encryptedSize);

        } catch (Exception ex) {
            log.error("Upload failed for taskId={}", taskId, ex);
            // Only attempt abort if the multipart upload was actually initiated
            if (uploadId != null) {
                abortMultipartUploadQuietly(r2Key, uploadId);
            }
            task.setStatus(TaskStatus.FAILED);
            taskEntryRepository.save(task);
        } finally {
            deleteTempFile(inputFile);
            deleteTempFile(encryptedTemp);
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    /**
     * Writes the FileCryptoService-compatible binary header followed by the
     * AES-GCM ciphertext (including the 16-byte GCM authentication tag appended
     * by {@code CipherOutputStream.close()}).
     */
    private void encryptToTempFile(Path source, Path dest, StreamEncryptedData meta) throws Exception {
        try (InputStream in = Files.newInputStream(source);
             DataOutputStream dos = new DataOutputStream(
                     new BufferedOutputStream(Files.newOutputStream(dest)))) {

            byte[] kekNameBytes = meta.kekName().getBytes(StandardCharsets.UTF_8);
            byte[] dekIv = meta.dekIv();
            byte[] encryptedDek = meta.encryptedDek();
            byte[] dataIv = meta.dataIv();

            // Identical validations to FileCryptoService — fields are length-prefixed with a single byte
            if (kekNameBytes.length > 255) throw new IllegalArgumentException("kekName too long");
            if (dekIv.length > 255) throw new IllegalArgumentException("dekIv too long");
            if (dataIv.length > 255) throw new IllegalArgumentException("dataIv too long");

            // Header — identical layout to FileCryptoService so decrypt() works unchanged
            dos.writeInt(MAGIC);
            dos.writeByte(kekNameBytes.length);
            dos.write(kekNameBytes);
            dos.writeByte(dekIv.length);
            dos.write(dekIv);
            dos.writeShort(encryptedDek.length);
            dos.write(encryptedDek);
            dos.writeByte(dataIv.length);
            dos.write(dataIv);

            // Ciphertext — CipherOutputStream.close() flushes the GCM auth tag
            try (CipherOutputStream cos = new CipherOutputStream(dos, meta.dataCipher())) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Encryption interrupted");
                    }
                    cos.write(buf, 0, read);
                }
            }
        }
    }

    /**
     * Reads the encrypted temp file sequentially and uploads each 5 MiB slice
     * as a separate S3 part. The final part may be smaller than 5 MiB.
     */
    private List<CompletedPart> uploadParts(
            Path file, long fileSize, String r2Key, String uploadId) throws IOException {

        List<CompletedPart> parts = new ArrayList<>();
        int partNumber = 1;
        long remaining = fileSize;
        byte[] buffer = new byte[PART_SIZE];

        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            while (remaining > 0) {
                int toRead = (int) Math.min(PART_SIZE, remaining);
                int bytesRead = in.readNBytes(buffer, 0, toRead);
                if (bytesRead == 0) break;

                UploadPartResponse partResponse = s3Client.uploadPart(
                        UploadPartRequest.builder()
                                .bucket(bucket)
                                .key(r2Key)
                                .uploadId(uploadId)
                                .partNumber(partNumber)
                                .contentLength((long) bytesRead)
                                .build(),
                        RequestBody.fromBytes(
                                bytesRead == buffer.length
                                        ? buffer
                                        : java.util.Arrays.copyOf(buffer, bytesRead))
                );

                parts.add(CompletedPart.builder()
                        .partNumber(partNumber)
                        .eTag(partResponse.eTag())
                        .build());

                log.debug("Uploaded part {}: taskKey={} bytes={}", partNumber, r2Key, bytesRead);
                partNumber++;
                remaining -= bytesRead;
            }
        }
        return parts;
    }

    private String buildR2Key(UUID taskId, String originalFilename) {
        String safe = (originalFilename == null ? "file" : originalFilename)
                .replaceAll("[^a-zA-Z0-9._-]", "_");
        return "photos/" + taskId + "/" + safe + ".enc";
    }

    private void abortMultipartUploadQuietly(String r2Key, String uploadId) {
        if (uploadId == null || r2Key == null) return;
        try {
            s3Client.abortMultipartUpload(
                    AbortMultipartUploadRequest.builder()
                            .bucket(bucket)
                            .key(r2Key)
                            .uploadId(uploadId)
                            .build()
            );
            log.warn("Aborted incomplete multipart upload: uploadId={} key={}", uploadId, r2Key);
        } catch (Exception e) {
            log.error("Failed to abort multipart upload: uploadId={} key={}", uploadId, r2Key, e);
        }
    }

    private void deleteTempFile(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete temp file: {}", path, e);
        }
    }
}
