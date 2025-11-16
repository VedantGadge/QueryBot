package com.vedant.querybot.service;

import com.vedant.querybot.entity.QueryHistory;
import com.vedant.querybot.entity.UploadedTableMetadata;
import com.vedant.querybot.repository.QueryHistoryRepository;
import com.vedant.querybot.repository.UploadedTableMetadataRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryServiceTest {

    @Test
    void executesSelectAndStoresHistory() {
        LLMService llm = mock(LLMService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        QueryHistoryRepository repo = mock(QueryHistoryRepository.class);
        UploadedTableMetadataRepository metaRepo = mock(UploadedTableMetadataRepository.class);

        // Provide a latest uploaded table metadata so the service doesn't throw
        UploadedTableMetadata meta = new UploadedTableMetadata();
        meta.setTableName("my_table");
        meta.setColumnsJson("{\"a\":\"a\"}");
        when(metaRepo.findAll()).thenReturn(List.of(meta));

        when(llm.generateSqlFromNl(anyString(), anyString(), anyList())).thenReturn("SELECT * FROM my_table LIMIT 10");
        when(jdbc.queryForList("SELECT * FROM my_table LIMIT 10")).thenReturn(List.of(Map.of("a", 1)));

        QueryService svc = new QueryService(llm, jdbc, repo, metaRepo);
        var result = svc.executeNlQueryWithSummary("show me data", "my_table", null);

        assertNotNull(result);
        assertEquals(1, result.rows().size());
        verify(repo, times(1)).save(any(QueryHistory.class));
    }
}
