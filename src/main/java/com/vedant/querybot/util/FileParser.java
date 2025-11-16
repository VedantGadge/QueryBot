package com.vedant.querybot.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import org.apache.poi.ss.usermodel.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.util.*;

/**
 * Parses uploaded files into a list of maps (each map is a row column->string).
 * Uses OpenCSV, Jackson, Apache POI. Errors bubble up as IOException.
 */
public class FileParser {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static List<Map<String, String>> parse(MultipartFile file) throws IOException {
        String name = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        if (name.endsWith(".csv")) return parseCsv(file.getInputStream());
        if (name.endsWith(".json")) return parseJson(file.getInputStream());
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) return parseExcel(file.getInputStream());
        // fallback: try CSV
        return parseCsv(file.getInputStream());
    }

    private static List<Map<String, String>> parseCsv(InputStream in) throws IOException {
        try (CSVReader reader = new CSVReader(new InputStreamReader(in))) {
            List<String[]> all = reader.readAll();
            if (all.isEmpty()) return Collections.emptyList();
            String[] headers = all.get(0);
            List<Map<String, String>> rows = new ArrayList<>();
            for (int i = 1; i < all.size(); i++) {
                String[] row = all.get(i);
                Map<String, String> map = new LinkedHashMap<>();
                for (int c = 0; c < headers.length; c++) {
                    String key = headers[c] != null ? headers[c] : ("col" + c);
                    String val = c < row.length ? row[c] : null;
                    map.put(key, val);
                }
                rows.add(map);
            }
            return rows;
        } catch (CsvException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<Map<String, String>> parseJson(InputStream in) throws IOException {
        // Expecting an array of objects: [ { ... }, { ...} ]
        List<Map<String, Object>> raw = mapper.readValue(in, new TypeReference<>() {});
        List<Map<String, String>> rows = new ArrayList<>();
        for (Map<String, Object> r : raw) {
            Map<String, String> map = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : r.entrySet()) {
                map.put(e.getKey(), e.getValue() != null ? e.getValue().toString() : null);
            }
            rows.add(map);
        }
        return rows;
    }

    private static List<Map<String, String>> parseExcel(InputStream in) throws IOException {
        Workbook workbook = WorkbookFactory.create(in);
        Sheet sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
        if (sheet == null) return Collections.emptyList();
        Iterator<Row> rowsIt = sheet.iterator();
        if (!rowsIt.hasNext()) return Collections.emptyList();
        Row headerRow = rowsIt.next();
        List<String> headers = new ArrayList<>();
        for (Cell c : headerRow) headers.add(c.getStringCellValue());

        List<Map<String, String>> rows = new ArrayList<>();
        while (rowsIt.hasNext()) {
            Row r = rowsIt.next();
            Map<String, String> map = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                Cell c = r.getCell(i);
                map.put(headers.get(i), cellToString(c));
            }
            rows.add(map);
        }
        return rows;
    }

    private static String cellToString(Cell c) {
        if (c == null) return null;
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(c)) yield c.getLocalDateTimeCellValue().toString();
                else yield Double.toString(c.getNumericCellValue());
            }
            case BOOLEAN -> Boolean.toString(c.getBooleanCellValue());
            case FORMULA -> c.getCellFormula();
            default -> null;
        };
    }
}
