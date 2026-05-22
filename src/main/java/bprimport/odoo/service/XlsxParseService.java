package bprimport.odoo.service;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

@Service
public class XlsxParseService {

    private static final Logger log = LoggerFactory.getLogger(XlsxParseService.class);

    @Value("${app.import.large-file-threshold-mb:10}")
    private int largeFileThresholdMb;

    // -------------------------------------------------------------------------
    // Sheet names
    // -------------------------------------------------------------------------

    public List<String> getSheetNames(Path filePath) throws Exception {
        List<String> names = new ArrayList<>();
        try (OPCPackage pkg = OPCPackage.open(filePath.toFile(), PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(pkg);
            XSSFReader.SheetIterator iter = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (iter.hasNext()) {
                try (InputStream ignored = iter.next()) {
                    names.add(iter.getSheetName());
                }
            }
        }
        return names;
    }

    // -------------------------------------------------------------------------
    // Headers (first row)
    // -------------------------------------------------------------------------

    public List<String> getHeaders(Path filePath, String sheetName) throws Exception {
        CollectorHandler handler = new CollectorHandler(1, false);
        parseSheet(filePath, sheetName, handler);
        if (handler.getRows().isEmpty()) return List.of();
        return new ArrayList<>(handler.getRows().get(0).values());
    }

    // -------------------------------------------------------------------------
    // Preview rows
    // -------------------------------------------------------------------------

    public bprimport.odoo.dto.PreviewResultDto getPreview(Path filePath, String sheetName, int limit) throws Exception {
        // Header row + data rows
        CollectorHandler handler = new CollectorHandler(limit + 1, true);
        parseSheet(filePath, sheetName, handler);

        List<Map<Integer, String>> rawRows = handler.getRows();
        if (rawRows.isEmpty()) {
            return new bprimport.odoo.dto.PreviewResultDto(List.of(), List.of(), 0);
        }

        // Build header list from first row
        Map<Integer, String> headerRow = rawRows.get(0);
        int colCount = headerRow.size();
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < colCount; i++) {
            headers.add(headerRow.getOrDefault(i, "Col " + (i + 1)));
        }

        // Build data rows as Map<colName, value>
        List<Map<String, String>> dataRows = new ArrayList<>();
        for (int r = 1; r < rawRows.size(); r++) {
            Map<Integer, String> raw = rawRows.get(r);
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                row.put(headers.get(i), raw.getOrDefault(i, ""));
            }
            dataRows.add(row);
        }

        int total = handler.getTotalRowsSeen() - 1; // exclude header
        return new bprimport.odoo.dto.PreviewResultDto(headers, dataRows, total);
    }

    // -------------------------------------------------------------------------
    // Count data rows (excluding header)
    // -------------------------------------------------------------------------

    public int countDataRows(Path filePath, String sheetName) throws Exception {
        CountingHandler handler = new CountingHandler();
        parseSheet(filePath, sheetName, handler);
        return Math.max(0, handler.getCount() - 1); // exclude header
    }

    // -------------------------------------------------------------------------
    // Stream all data rows (after header)
    // -------------------------------------------------------------------------

    public void processRows(Path filePath, String sheetName,
                            List<String> headers,
                            Consumer<Map<String, String>> rowConsumer) throws Exception {
        StreamingRowHandler handler = new StreamingRowHandler(headers, rowConsumer);
        parseSheet(filePath, sheetName, handler);
    }

    // -------------------------------------------------------------------------
    // Core SAX streaming parser
    // -------------------------------------------------------------------------

    private void parseSheet(Path filePath, String targetSheet, XSSFSheetXMLHandler.SheetContentsHandler handler) throws Exception {
        try (OPCPackage pkg = OPCPackage.open(filePath.toFile(), PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(pkg);
            SharedStrings sst = reader.getSharedStringsTable();
            StylesTable styles = reader.getStylesTable();

            XSSFSheetXMLHandler sheetXmlHandler = new XSSFSheetXMLHandler(
                styles, null, sst, handler, new DataFormatter(), false);

            XMLReader xmlReader = XMLHelper.newXMLReader();
            xmlReader.setContentHandler(sheetXmlHandler);

            XSSFReader.SheetIterator iter = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (iter.hasNext()) {
                try (InputStream sheetStream = iter.next()) {
                    if (iter.getSheetName().equals(targetSheet)) {
                        xmlReader.parse(new InputSource(sheetStream));
                        break;
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // SAX Handlers
    // -------------------------------------------------------------------------

    /** Collects up to maxRows rows into a list of Map<colIndex, value> */
    private static class CollectorHandler implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final int maxRows;
        private final boolean countAll;
        private final List<Map<Integer, String>> rows = new ArrayList<>();
        private Map<Integer, String> currentRow;
        private int totalRowsSeen = 0;

        CollectorHandler(int maxRows, boolean countAll) {
            this.maxRows = maxRows;
            this.countAll = countAll;
        }

        @Override
        public void startRow(int rowNum) {
            totalRowsSeen++;
            currentRow = rows.size() < maxRows ? new LinkedHashMap<>() : null;
        }

        @Override
        public void cell(String cellRef, String value, XSSFComment comment) {
            if (currentRow == null || value == null) return;
            int col = new CellReference(cellRef).getCol();
            currentRow.put(col, value.trim());
        }

        @Override
        public void endRow(int rowNum) {
            if (currentRow != null) {
                rows.add(currentRow);
            }
        }

        List<Map<Integer, String>> getRows() { return rows; }
        int getTotalRowsSeen() { return totalRowsSeen; }
    }

    /** Counts all rows */
    private static class CountingHandler implements XSSFSheetXMLHandler.SheetContentsHandler {
        private int count = 0;

        @Override public void startRow(int rowNum) { count++; }
        @Override public void cell(String ref, String val, XSSFComment c) {}
        @Override public void endRow(int rowNum) {}

        int getCount() { return count; }
    }

    /** Streams rows after the header to a consumer */
    private static class StreamingRowHandler implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final List<String> headers;
        private final Consumer<Map<String, String>> consumer;
        private Map<Integer, String> currentRow;
        private int rowNum = 0;

        StreamingRowHandler(List<String> headers, Consumer<Map<String, String>> consumer) {
            this.headers = headers;
            this.consumer = consumer;
        }

        @Override
        public void startRow(int num) {
            rowNum = num;
            currentRow = new LinkedHashMap<>();
        }

        @Override
        public void cell(String cellRef, String value, XSSFComment comment) {
            if (value == null) return;
            int col = new CellReference(cellRef).getCol();
            currentRow.put(col, value.trim());
        }

        @Override
        public void endRow(int num) {
            if (rowNum == 0) return; // skip header row (row 0)
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                row.put(headers.get(i), currentRow.getOrDefault(i, ""));
            }
            consumer.accept(row);
        }
    }
}
