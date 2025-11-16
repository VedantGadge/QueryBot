package com.vedant.querybot.controller;

import com.vedant.querybot.dto.NLQueryRequestDTO;
import com.vedant.querybot.dto.NLQueryResponseDTO;
import com.vedant.querybot.service.QueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/query")
public class QueryController {

    private final QueryService queryService;

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping("/nl")
    public ResponseEntity<NLQueryResponseDTO> nlQuery(@RequestBody NLQueryRequestDTO req, HttpServletRequest request) {
        try {
            String sessionId = request.getSession().getId();
            QueryService.QueryResult result = queryService.executeNlQueryWithSummary(req.getNlQuery(), req.getTargetTable(), sessionId);
            NLQueryResponseDTO dto = new NLQueryResponseDTO();
            dto.setSql(result.sql());
            dto.setRows(result.rows());
            dto.setMessage("OK");
            dto.setNlAnswer(result.nlAnswer());
            return ResponseEntity.ok(dto);
        } catch (IllegalArgumentException ex) {
            NLQueryResponseDTO dto = new NLQueryResponseDTO();
            dto.setMessage(ex.getMessage());
            return ResponseEntity.badRequest().body(dto);
        } catch (Exception ex) {
            NLQueryResponseDTO dto = new NLQueryResponseDTO();
            dto.setMessage("Execution error: " + ex.getMessage());
            return ResponseEntity.status(500).body(dto);
        }
    }

    @GetMapping("/history")
    public ResponseEntity<List<Map<String, String>>> getHistory(HttpServletRequest request) {
        String sessionId = request.getSession().getId();
        List<Map<String, String>> conv = queryService.getConversation(sessionId);
        return ResponseEntity.ok(conv);
    }

    @PostMapping("/memory")
    public ResponseEntity<Map<String, Object>> addMemory(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String role = body.getOrDefault("role", "assistant");
        String content = body.getOrDefault("content", "");
        String sessionId = request.getSession().getId();
        if (sessionId != null && !sessionId.isBlank() && content != null && !content.isBlank()) {
            queryService.addToMemory(sessionId, role, content);
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

}
