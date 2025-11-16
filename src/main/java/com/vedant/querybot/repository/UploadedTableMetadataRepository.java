package com.vedant.querybot.repository;

import com.vedant.querybot.entity.UploadedTableMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UploadedTableMetadataRepository extends JpaRepository<UploadedTableMetadata, Long> {
    Optional<UploadedTableMetadata> findByTableName(String tableName);
}
