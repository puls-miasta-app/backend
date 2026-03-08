package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.TaskResponse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.TaskEntry;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.TaskStatus;
import com.github.PulsMiastaApp.PulsMiasta.Repository.TaskEntryRepository;
import com.github.PulsMiastaApp.PulsMiasta.Service.PhotoUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequestMapping("/v1/photos")
@RequiredArgsConstructor
public class PhotoUploadController {

    private final TaskEntryRepository taskEntryRepository;
    private final PhotoUploadService photoUploadService;

    /**
     * Accepts a photo upload and immediately returns 202 Accepted with a task ID.
     *
     * <p>The file is transferred to a temp file so that Spring can release the
     * multipart resources after the request ends, while the async task continues
     * reading from the temp file in its own thread.
     *
     * @param file the photo to upload
     * @return 202 with {@link TaskResponse} containing the tracking task ID
     * @throws IOException if the file cannot be staged to a temp location
     */
    @PostMapping("/upload")
    public ResponseEntity<TaskResponse> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "Uploaded file must not be empty");
        }

        TaskEntry task = new TaskEntry();
        task.setStatus(TaskStatus.PENDING);
        task = taskEntryRepository.save(task);

        // Copy to a temp file before returning — the async thread must not rely on
        // Spring's internal multipart temp file which may be deleted after the request.
        Path inputTemp = Files.createTempFile("pm-in-" + task.getId() + "-", ".raw");
        try {
            file.transferTo(inputTemp);
        } catch (IOException e) {
            // Staging failed; clean up the orphaned temp file before re-throwing
            Files.deleteIfExists(inputTemp);
            throw e;
        }

        photoUploadService.processUpload(task.getId(), inputTemp, file.getOriginalFilename());

        return ResponseEntity.accepted().body(new TaskResponse(task.getId()));
    }
}
