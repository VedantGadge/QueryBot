package com.vedant.querybot.controller;

import com.vedant.querybot.dto.UploadResponseDTO;
import com.vedant.querybot.entity.UploadedTableMetadata;
import com.vedant.querybot.service.FileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/files")
public class FileUploadController {

    private final FileService fileService;

    public FileUploadController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/upload")
    public ResponseEntity<UploadResponseDTO> upload(@RequestPart("file") MultipartFile file) {
        try {
            UploadedTableMetadata meta = fileService.processUpload(file);
            UploadResponseDTO resp = new UploadResponseDTO(meta.getTableName(), meta.getRowCount(), "Uploaded and imported");
            return ResponseEntity.ok(resp);
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(new UploadResponseDTO(null, 0, "Upload failed: " + ex.getMessage()));
        }
    }
}
