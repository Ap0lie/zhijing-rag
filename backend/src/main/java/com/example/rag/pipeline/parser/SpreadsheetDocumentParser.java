package com.example.rag.pipeline.parser;

import com.example.rag.document.OoxmlPackageInspector;
import com.example.rag.document.OoxmlPackageInspector.OoxmlValidationException;
import com.example.rag.persistence.DocumentFormat;
import com.example.rag.persistence.SourceLocatorKind;
import com.example.rag.persistence.SourceUnitKind;
import com.example.rag.pipeline.ParserInput;
import com.example.rag.pipeline.ParserProcessingException;
import com.example.rag.pipeline.ParserProviderKind;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Bounded, network-free XLSX/CSV parser. XLSX uses POI's event model and CSV
 * consumes records from a Reader; neither path evaluates formulas.
 */
@Component
public final class SpreadsheetDocumentParser {

    public static final String POI_VERSION = "5.5.1";
    public static final String CSV_VERSION = "1.14.1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final int MAX_SHEETS = 128;
    private static final int MAX_ROWS = 20_000;
    private static final int MAX_COLUMNS = 128;
    private static final int MAX_CELLS = 100_000;
    private static final int MAX_MERGED_REGIONS = 10_000;
    private static final long MAX_MERGED_AREA = MAX_CELLS;
    private static final int MAX_CELL_CHARACTERS = 32_000;
    private static final int MAX_TEXT_CHARACTERS = 10_000_000;
    private static final int ROW_WINDOW_SIZE = 20;
    private static final int CSV_SAMPLE_CHARACTERS = 64 * 1024;

    private final OoxmlPackageInspector inspector;

    public SpreadsheetDocumentParser(OoxmlPackageInspector inspector) {
        this.inspector = inspector;
    }

    public ParsedDocument parse(
            ParserInput input,
            ChunkingProfile profile,
            ParserProviderKind provider
    ) throws IOException, ParserProcessingException {
        requireProvider(input.documentFormat(), provider);
        Instant startedAt = Instant.now();
        WorkbookDraft draft = input.documentFormat() == DocumentFormat.XLSX
                ? parseXlsx(input, provider)
                : parseCsv(input, provider);
        return assemble(input, profile, provider, draft, startedAt);
    }

    private WorkbookDraft parseXlsx(
            ParserInput input,
            ParserProviderKind provider
    ) throws IOException, ParserProcessingException {
        OoxmlPackageInspector.Inspection inspection;
        try {
            inspection = inspector.inspect(input.path(), DocumentFormat.XLSX);
        } catch (OoxmlValidationException exception) {
            throw new ParserProcessingException(
                    exception.code(),
                    exception.getMessage(),
                    exception
            );
        }
        WorkbookDraft draft = new WorkbookDraft(
                provider,
                "binary",
                null,
                inspection
        );
        try (OPCPackage pkg = OPCPackage.open(
                input.path().toFile(),
                PackageAccess.READ
        )) {
            XSSFReader reader = new XSSFReader(pkg);
            Map<String, String> states = workbookStates(
                    reader.getWorkbookData()
            );
            StylesTable styles = reader.getStylesTable();
            SharedStrings strings = reader.getSharedStringsTable();
            var rawSheets = reader.getSheetsData();
            if (!(rawSheets instanceof XSSFReader.SheetIterator sheets)) {
                throw new ParserProcessingException(
                        "XLSX_SHEET_STREAM_UNAVAILABLE",
                        "Workbook sheet stream is unavailable"
                );
            }
            int sheetOrder = 0;
            while (sheets.hasNext()) {
                sheetOrder++;
                requireLimit(
                        sheetOrder,
                        MAX_SHEETS,
                        "SPREADSHEET_SHEET_LIMIT",
                        "Workbook contains too many worksheets"
                );
                try (InputStream ignored = sheets.next()) {
                    String rawName = sheets.getSheetName();
                    if (!"visible".equals(states.getOrDefault(
                            rawName, "visible"
                    ))) {
                        draft.hiddenSheets++;
                        continue;
                    }
                    String name = normalized(rawName);
                    if (name.isBlank()) {
                        name = "Sheet " + sheetOrder;
                    }
                    PackagePart part = sheets.getSheetPart();
                    SheetDraft sheet = readXlsxSheet(
                            part,
                            name,
                            sheetOrder,
                            styles,
                            strings
                    );
                    draft.accept(sheet);
                }
            }
        } catch (ParserProcessingException exception) {
            throw exception;
        } catch (SpreadsheetLimitException exception) {
            throw new ParserProcessingException(
                    exception.code,
                    exception.getMessage(),
                    exception
            );
        } catch (Exception exception) {
            SpreadsheetLimitException limit = limitCause(exception);
            if (limit != null) {
                throw new ParserProcessingException(
                        limit.code,
                        limit.getMessage(),
                        limit
                );
            }
            throw new ParserProcessingException(
                    "XLSX_PARSE_FAILED",
                    "Workbook could not be parsed safely",
                    exception
            );
        }
        draft.requireContent();
        return draft;
    }

    private static SheetDraft readXlsxSheet(
            PackagePart part,
            String name,
            int order,
            StylesTable styles,
            SharedStrings strings
    ) throws Exception {
        SheetMetadata metadata;
        try (InputStream input = part.getInputStream()) {
            metadata = sheetMetadata(input);
        }
        Map<String, String> displays = new LinkedHashMap<>();
        long[] displayCharacters = {0};
        DataFormatter formatter = new DataFormatter(Locale.ROOT, true);
        try (InputStream input = part.getInputStream()) {
            XMLReader xml = XMLHelper.newXMLReader();
            xml.setContentHandler(new XSSFSheetXMLHandler(
                    styles,
                    strings,
                    new XSSFSheetXMLHandler.SheetContentsHandler() {
                        @Override
                        public void startRow(int rowNum) {
                        }

                        @Override
                        public void endRow(int rowNum) {
                        }

                        @Override
                        public void cell(
                                String reference,
                                String formattedValue,
                                XSSFComment comment
                        ) {
                            if (reference != null) {
                                if (!displays.containsKey(reference)) {
                                    requireRuntimeLimit(
                                            displays.size() + 1L,
                                            MAX_CELLS,
                                            "SPREADSHEET_CELL_LIMIT",
                                            "Spreadsheet contains too many visible cells"
                                    );
                                }
                                String value = normalized(formattedValue);
                                requireRuntimeLimit(
                                        value.length(),
                                        MAX_CELL_CHARACTERS,
                                        "SPREADSHEET_CELL_LIMIT",
                                        "Spreadsheet cell text exceeds the safe limit"
                                );
                                displayCharacters[0] += value.length();
                                requireRuntimeLimit(
                                        displayCharacters[0],
                                        MAX_TEXT_CHARACTERS,
                                        "SPREADSHEET_TEXT_LIMIT",
                                        "Spreadsheet contains too much indexable text"
                                );
                                displays.put(
                                        reference,
                                        value
                                );
                            }
                        }

                        @Override
                        public void headerFooter(
                                String text,
                                boolean isHeader,
                                String tagName
                        ) {
                        }
                    },
                    formatter,
                    false
            ));
            xml.parse(new InputSource(input));
        }

        Set<String> references = new HashSet<>(displays.keySet());
        references.addAll(metadata.cells.keySet());
        NavigableMap<Integer, NavigableMap<Integer, CellValue>> rows =
                new TreeMap<>();
        int materializedCells = 0;
        long materializedCharacters = 0;
        for (String reference : references.stream().sorted(
                Comparator.comparingInt((String value) ->
                                new CellReference(value).getRow())
                        .thenComparingInt(value ->
                                new CellReference(value).getCol())
        ).toList()) {
            CellReference cellReference = new CellReference(reference);
            int row = cellReference.getRow();
            int column = cellReference.getCol();
            if (metadata.hiddenRows.contains(row)
                    || hidden(column, metadata.hiddenColumns)) {
                continue;
            }
            CellMetadata raw = metadata.cells.getOrDefault(
                    reference,
                    CellMetadata.EMPTY
            );
            String display = displays.getOrDefault(reference, "");
            String formula = raw.formula == null || raw.formula.isBlank()
                    ? null
                    : "=" + raw.formula.strip();
            String text = !display.isBlank()
                    ? display
                    : formula == null ? normalized(raw.rawValue) : formula;
            if (text.length() > MAX_CELL_CHARACTERS) {
                throw new ParserProcessingException(
                        "SPREADSHEET_CELL_LIMIT",
                        "Spreadsheet cell text exceeds the safe limit"
                );
            }
            String numberFormat = null;
            String cellType = formula == null ? cellType(raw.type) : "FORMULA";
            if (raw.styleIndex >= 0) {
                XSSFCellStyle style = styles.getStyleAt(raw.styleIndex);
                numberFormat = style.getDataFormatString();
                if (formula == null && DateUtil.isADateFormat(
                        style.getDataFormat(),
                        numberFormat
                )) {
                    cellType = "DATE";
                }
            }
            String rawValue = formula == null
                    && "TEXT".equals(cellType(raw.type))
                    ? display
                    : normalized(raw.rawValue);
            requireLimit(
                    ++materializedCells,
                    MAX_CELLS,
                    "SPREADSHEET_CELL_LIMIT",
                    "Spreadsheet contains too many visible cells"
            );
            materializedCharacters += text.length()
                    + rawValue.length() + display.length()
                    + (formula == null ? 0 : formula.length());
            requireLimit(
                    materializedCharacters,
                    MAX_TEXT_CHARACTERS,
                    "SPREADSHEET_TEXT_LIMIT",
                    "Spreadsheet contains too much indexable text"
            );
            rows.computeIfAbsent(row, ignored -> new TreeMap<>()).put(
                    column,
                    new CellValue(
                            reference,
                            row,
                            column,
                            text,
                            rawValue,
                            display,
                            formula,
                            numberFormat,
                            cellType,
                            1,
                            1
                    )
            );
        }
        applyMerges(rows, metadata.merges, metadata.hiddenRows,
                metadata.hiddenColumns);
        return new SheetDraft(
                name,
                order,
                rows,
                metadata.hiddenRows.size(),
                metadata.hiddenColumns.stream()
                        .mapToInt(range -> range.end - range.start + 1)
                        .sum(),
                metadata.formulaErrors
        );
    }

    private static WorkbookDraft parseCsv(
            ParserInput input,
            ParserProviderKind provider
    ) throws IOException, ParserProcessingException {
        Encoding encoding = detectEncoding(input.path());
        char delimiter = detectDelimiter(input.path(), encoding);
        WorkbookDraft draft = new WorkbookDraft(
                provider,
                encoding.name,
                delimiter,
                null
        );
        NavigableMap<Integer, NavigableMap<Integer, CellValue>> rows =
                new TreeMap<>();
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setQuote('"')
                .setIgnoreEmptyLines(false)
                .get();
        try (Reader source = reader(input.path(), encoding);
             CSVParser parser = CSVParser.builder()
                     .setReader(source)
                     .setFormat(format)
                     .get()) {
            int rowIndex = 0;
            for (CSVRecord record : parser) {
                rowIndex++;
                requireLimit(
                        rowIndex,
                        MAX_ROWS,
                        "SPREADSHEET_ROW_LIMIT",
                        "CSV contains too many rows"
                );
                requireLimit(
                        record.size(),
                        MAX_COLUMNS,
                        "SPREADSHEET_COLUMN_LIMIT",
                        "CSV contains too many columns"
                );
                draft.reserveCells(record.size());
                NavigableMap<Integer, CellValue> cells = new TreeMap<>();
                boolean nonEmpty = false;
                for (int column = 0; column < record.size(); column++) {
                    String display = normalized(record.get(column));
                    requireLimit(
                            display.length(),
                            MAX_CELL_CHARACTERS,
                            "SPREADSHEET_CELL_LIMIT",
                            "CSV cell text exceeds the safe limit"
                    );
                    draft.reserveCharacters(display.length());
                    nonEmpty |= !display.isBlank();
                    String reference = new CellReference(
                            rowIndex - 1,
                            column
                    ).formatAsString();
                    cells.put(
                            column,
                            new CellValue(
                                    reference,
                                    rowIndex - 1,
                                    column,
                                    display,
                                    display,
                                    display,
                                    null,
                                    null,
                                    numeric(display) ? "NUMBER" : "TEXT",
                                    1,
                                    1
                            )
                    );
                }
                if (nonEmpty) {
                    rows.put(rowIndex - 1, cells);
                } else {
                    draft.emptyRows++;
                }
            }
        } catch (UncheckedIOException exception) {
            throw new ParserProcessingException(
                    "CSV_ENCODING_INVALID",
                    "CSV contains invalid encoded text",
                    exception
            );
        }
        draft.accept(new SheetDraft(
                "CSV",
                1,
                rows,
                0,
                0,
                0
        ));
        draft.requireContent();
        return draft;
    }

    private static ParsedDocument assemble(
            ParserInput input,
            ChunkingProfile profile,
            ParserProviderKind provider,
            WorkbookDraft draft,
            Instant startedAt
    ) throws ParserProcessingException {
        List<ParsedDocument.SourceUnit> units = new ArrayList<>();
        List<ParsedDocument.ContentBlock> blocks = new ArrayList<>();
        List<ParsedStructure.Table> tables = new ArrayList<>();
        String normalization = input.documentFormat().name()
                .toLowerCase(Locale.ROOT) + "-sheet-cell-range-v1";
        int tableOrder = 0;
        int cellCount = 0;
        int characters = 0;
        StringBuilder markdown = new StringBuilder();

        for (SheetDraft sheet : draft.sheets) {
            List<Integer> rowNumbers = sheet.rows.keySet().stream().toList();
            if (rowNumbers.isEmpty()) {
                draft.emptySheets++;
                continue;
            }
            List<Integer> visibleColumns = sheet.rows.values().stream()
                    .flatMap(row -> row.keySet().stream())
                    .distinct()
                    .sorted()
                    .toList();
            requireLimit(
                    visibleColumns.size(),
                    MAX_COLUMNS,
                    "SPREADSHEET_COLUMN_LIMIT",
                    "Spreadsheet contains too many visible columns"
            );
            int firstColumn = visibleColumns.getFirst();
            int lastColumn = visibleColumns.getLast();
            int headerRows = detectHeaderRows(sheet, rowNumbers);
            List<Integer> headerNumbers = rowNumbers.stream()
                    .limit(headerRows)
                    .toList();
            List<Integer> dataRows = rowNumbers.stream()
                    .skip(headerRows)
                    .toList();
            if (dataRows.isEmpty()) {
                dataRows = headerNumbers;
                headerNumbers = List.of();
            }

            String sheetRange = range(
                    sheet.name,
                    rowNumbers.getFirst(),
                    rowNumbers.getLast(),
                    firstColumn,
                    lastColumn
            );
            String heading = "工作表：" + sheet.name;
            int headingUnit = addUnit(
                    units,
                    input,
                    SourceUnitKind.SHEET,
                    "sheet:" + sheet.order + ":heading",
                    heading,
                    "工作表 " + sheet.name,
                    normalization,
                    sheetRange
            );
            addBlock(
                    blocks,
                    units.get(headingUnit),
                    input,
                    profile,
                    ParsedDocument.BlockType.HEADING,
                    heading,
                    List.of(sheet.name),
                    sheetRange,
                    normalization
            );
            markdown.append("# ").append(sheet.name).append("\n\n");

            for (int offset = 0; offset < dataRows.size();
                 offset += ROW_WINDOW_SIZE) {
                List<Integer> window = dataRows.subList(
                        offset,
                        Math.min(dataRows.size(), offset + ROW_WINDOW_SIZE)
                );
                List<Integer> included = new ArrayList<>(headerNumbers);
                for (Integer row : window) {
                    if (!included.contains(row)) {
                        included.add(row);
                    }
                }
                int firstData = window.getFirst();
                int lastData = window.getLast();
                String dataRange = range(
                        sheet.name,
                        firstData,
                        lastData,
                        firstColumn,
                        lastColumn
                );
                String locator = headerNumbers.isEmpty()
                        || headerNumbers.contains(firstData)
                        ? dataRange
                        : range(
                                sheet.name,
                                headerNumbers.getFirst(),
                                headerNumbers.getLast(),
                                firstColumn,
                                lastColumn
                        ) + "," + cellRange(dataRange);
                List<List<CellValue>> renderedRows = new ArrayList<>();
                List<ParsedStructure.Cell> cells = new ArrayList<>();
                for (int localRow = 0; localRow < included.size(); localRow++) {
                    int sourceRow = included.get(localRow);
                    boolean header = headerNumbers.contains(sourceRow);
                    List<CellValue> rendered = new ArrayList<>();
                    for (int localColumn = 0;
                         localColumn < visibleColumns.size();
                         localColumn++) {
                        int column = visibleColumns.get(localColumn);
                        CellValue value = sheet.rows
                                .getOrDefault(sourceRow, new TreeMap<>())
                                .get(column);
                        if (value == null) {
                            value = CellValue.blank(sourceRow, column);
                        }
                        if (value.coveredByMerge) {
                            continue;
                        }
                        rendered.add(value);
                        String display = value.displayValue.isBlank()
                                ? value.text
                                : value.displayValue;
                        cells.add(new ParsedStructure.Cell(
                                FormatNeutralChunker.stableUuid(
                                        "spreadsheet-cell",
                                        input.revisionId(),
                                        sheet.order,
                                        firstData,
                                        lastData,
                                        value.reference
                                ),
                                localRow,
                                localColumn,
                                value.rowSpan,
                                value.columnSpan,
                                header,
                                display,
                                FormatNeutralChunker.sha256(display),
                                value.reference,
                                value.cellType,
                                value.rawValue,
                                value.displayValue,
                                value.formulaText,
                                value.numberFormat
                        ));
                    }
                    renderedRows.add(rendered);
                }
                cellCount += cells.size();
                requireLimit(
                        cellCount,
                        MAX_CELLS,
                        "SPREADSHEET_CELL_LIMIT",
                        "Spreadsheet contains too many visible cells"
                );
                String text = tableMarkdown(renderedRows);
                if (text.isBlank()) {
                    continue;
                }
                int unit = addUnit(
                        units,
                        input,
                        SourceUnitKind.SHEET,
                        "sheet:" + sheet.order + ":rows:"
                                + firstData + "-" + lastData,
                        text,
                        locator,
                        normalization,
                        locator
                );
                int blockOrder = addBlock(
                        blocks,
                        units.get(unit),
                        input,
                        profile,
                        ParsedDocument.BlockType.TABLE,
                        text,
                        List.of(sheet.name),
                        locator,
                        normalization
                );
                ParsedStructure.BoundingBox fullUnit =
                        new ParsedStructure.BoundingBox(
                                units.get(unit).id(),
                                units.get(unit).order(),
                                SourceUnitKind.SHEET,
                                0,
                                0,
                                1000,
                                1000
                        );
                tables.add(new ParsedStructure.Table(
                        FormatNeutralChunker.stableUuid(
                                "spreadsheet-table",
                                input.revisionId(),
                                sheet.order,
                                firstData,
                                lastData
                        ),
                        tableOrder++,
                        blockOrder,
                        null,
                        fullUnit,
                        sheet.name + " · " + cellRange(locator),
                        tableHtml(cells),
                        FormatNeutralChunker.sha256(text),
                        cells
                ));
                markdown.append(text).append("\n\n");
                characters += text.length();
                requireLimit(
                        characters,
                        MAX_TEXT_CHARACTERS,
                        "SPREADSHEET_TEXT_LIMIT",
                        "Spreadsheet contains too much indexable text"
                );
            }
        }
        if (blocks.isEmpty() || tables.isEmpty()) {
            throw new ParserProcessingException(
                    "SPREADSHEET_EMPTY",
                    "Spreadsheet contains no visible indexable table"
            );
        }

        String markdownText = markdown.toString().strip() + "\n";
        String parserVersion = provider == ParserProviderKind.XLSX_POI
                ? "xlsx-poi-event-" + POI_VERSION + "-v1"
                : "csv-stream-" + CSV_VERSION + "-v1";
        String parserRevision = provider == ParserProviderKind.XLSX_POI
                ? "apache-poi-" + POI_VERSION
                : "apache-commons-csv-" + CSV_VERSION;
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("schema", "spreadsheet-structured-v1");
        manifest.put("parser", provider.name());
        manifest.put("parserVersion", parserVersion);
        manifest.put("parserRevision", parserRevision);
        manifest.put("inputHash", input.inputHash());
        manifest.put("outputHash", FormatNeutralChunker.sha256(markdownText));
        manifest.put("encoding", draft.encoding);
        if (draft.delimiter != null) {
            manifest.put("delimiter", delimiterLabel(draft.delimiter));
        }
        manifest.put(
                "decisionCode",
                provider == ParserProviderKind.XLSX_POI
                        ? "XLSX_EVENT_STREAM"
                        : "CSV_" + delimiterLabel(draft.delimiter)
                        + "_HEADER_DETECT"
        );
        manifest.put("sheetCount", draft.sheets.size());
        manifest.put("hiddenSheetsSkipped", draft.hiddenSheets);
        manifest.put("hiddenRowsSkipped", draft.hiddenRows);
        manifest.put("hiddenColumnsSkipped", draft.hiddenColumns);
        manifest.put("emptySheetsSkipped", draft.emptySheets);
        manifest.put("emptyRowsSkipped", draft.emptyRows);
        manifest.put("formulaErrorCount", draft.formulaErrors);
        manifest.put("tableRegionCount", tables.size());
        manifest.put("cellCount", cellCount);
        manifest.put("formulaEvaluationPerformed", false);
        manifest.put("networkResourcesFetched", 0);
        manifest.put(
                "sanitization",
                "visible sheets/rows/columns only; formulas preserved as text with cached display values and never evaluated"
        );
        ParsedStructure.PackageMetadata metadata =
                new ParsedStructure.PackageMetadata(
                        provider.name(),
                        parserVersion,
                        parserRevision,
                        input.inputHash(),
                        FormatNeutralChunker.sha256(markdownText),
                        "spreadsheet-structured-v1",
                        json(manifest)
                );
        List<ParsedDocument.Chunk> chunks =
                FormatNeutralChunker.createChunks(
                        input.revisionId(),
                        blocks,
                        profile
                );
        int tokens = blocks.stream()
                .mapToInt(ParsedDocument.ContentBlock::tokenCount)
                .sum();
        return ParsedPackageIntegrity.seal(new ParsedDocument(
                input.documentId(),
                input.revisionId(),
                input.documentFormat(),
                provider,
                units,
                markdownText,
                blocks,
                chunks,
                metadata,
                tables,
                List.of(),
                blocks.stream()
                        .mapToInt(ParsedDocument.ContentBlock::characterCount)
                        .sum(),
                tokens,
                profile.version(),
                parserVersion,
                profile.chunkerVersion(),
                profile.tokenCounterVersion(),
                Duration.between(startedAt, Instant.now()).toMillis()
        ));
    }

    private static int addUnit(
            List<ParsedDocument.SourceUnit> units,
            ParserInput input,
            SourceUnitKind kind,
            String stableAddress,
            String text,
            String sourceLabel,
            String normalization,
            String range
    ) throws ParserProcessingException {
        String hash = FormatNeutralChunker.sha256(text);
        ObjectNode label = JSON.createObjectNode();
        label.put("sourceLabel", sourceLabel);
        label.put("cellRange", range);
        label.put("documentFormat", input.documentFormat().name());
        units.add(new ParsedDocument.SourceUnit(
                FormatNeutralChunker.stableUuid(
                        "source-unit",
                        input.revisionId(),
                        kind,
                        stableAddress,
                        hash
                ),
                units.size() + 1,
                kind,
                stableAddress,
                text,
                hash,
                normalization,
                json(label)
        ));
        return units.size() - 1;
    }

    private static int addBlock(
            List<ParsedDocument.ContentBlock> blocks,
            ParsedDocument.SourceUnit unit,
            ParserInput input,
            ChunkingProfile profile,
            ParsedDocument.BlockType type,
            String text,
            List<String> headingPath,
            String address,
            String normalization
    ) {
        String hash = FormatNeutralChunker.sha256(text);
        ParsedDocument.SourceSpan span = new ParsedDocument.SourceSpan(
                unit.id(),
                unit.id(),
                unit.order(),
                unit.order(),
                SourceLocatorKind.CELL_RANGE,
                address + "#chars=0-" + text.length(),
                0,
                text.length(),
                0,
                text.length(),
                hash,
                normalization,
                List.of()
        );
        int order = blocks.size();
        blocks.add(new ParsedDocument.ContentBlock(
                FormatNeutralChunker.stableUuid(
                        "block",
                        input.revisionId(),
                        order,
                        unit.id(),
                        hash
                ),
                order,
                type,
                text,
                headingPath,
                text.length(),
                FormatNeutralChunker.countTokens(text),
                profile.tokenCounterVersion(),
                span
        ));
        return order;
    }

    private static int detectHeaderRows(
            SheetDraft sheet,
            List<Integer> rows
    ) {
        if (rows.size() < 2 || !looksLikeHeader(sheet.rows.get(rows.getFirst()))) {
            return 0;
        }
        if (rows.size() >= 3
                && looksLikeHeader(sheet.rows.get(rows.get(1)))
                && !looksLikeHeader(sheet.rows.get(rows.get(2)))) {
            return 2;
        }
        return 1;
    }

    private static boolean looksLikeHeader(Map<Integer, CellValue> row) {
        if (row == null || row.isEmpty()) {
            return false;
        }
        int nonEmpty = 0;
        int text = 0;
        Set<String> unique = new HashSet<>();
        for (CellValue value : row.values()) {
            String display = value.displayValue.strip();
            if (display.isEmpty()) {
                continue;
            }
            nonEmpty++;
            if (!numeric(display)) {
                text++;
            }
            unique.add(display.toLowerCase(Locale.ROOT));
        }
        return nonEmpty > 0
                && unique.size() == nonEmpty
                && text * 2 >= nonEmpty;
    }

    private static String tableMarkdown(List<List<CellValue>> rows) {
        if (rows.isEmpty()) {
            return "";
        }
        int columns = rows.stream().mapToInt(List::size).max().orElse(1);
        StringBuilder value = new StringBuilder();
        appendMarkdownRow(value, rows.getFirst(), columns);
        value.append('\n');
        value.append("| ").append("--- | ".repeat(columns));
        for (int row = 1; row < rows.size(); row++) {
            value.append('\n');
            appendMarkdownRow(value, rows.get(row), columns);
        }
        return value.toString();
    }

    private static void appendMarkdownRow(
            StringBuilder target,
            List<CellValue> row,
            int columns
    ) {
        target.append('|');
        for (int column = 0; column < columns; column++) {
            CellValue cell = column < row.size() ? row.get(column) : null;
            String text = cell == null ? "" : cell.indexText();
            target.append(' ')
                    .append(text.replace("|", "\\|").replace("\n", " "))
                    .append(" |");
        }
    }

    private static String tableHtml(List<ParsedStructure.Cell> cells) {
        StringBuilder html = new StringBuilder("<table><tbody>");
        int currentRow = -1;
        for (ParsedStructure.Cell cell : cells) {
            if (cell.rowIndex() != currentRow) {
                if (currentRow >= 0) {
                    html.append("</tr>");
                }
                html.append("<tr>");
                currentRow = cell.rowIndex();
            }
            String tag = cell.header() ? "th" : "td";
            html.append('<').append(tag);
            if (cell.rowSpan() > 1) {
                html.append(" rowspan=\"").append(cell.rowSpan()).append('"');
            }
            if (cell.columnSpan() > 1) {
                html.append(" colspan=\"").append(cell.columnSpan()).append('"');
            }
            html.append('>')
                    .append(escapeHtml(cell.text()))
                    .append("</").append(tag).append('>');
        }
        if (currentRow >= 0) {
            html.append("</tr>");
        }
        return html.append("</tbody></table>").toString();
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String range(
            String sheet,
            int firstRow,
            int lastRow,
            int firstColumn,
            int lastColumn
    ) {
        return sheetName(sheet) + "!"
                + CellReference.convertNumToColString(firstColumn)
                + (firstRow + 1) + ":"
                + CellReference.convertNumToColString(lastColumn)
                + (lastRow + 1);
    }

    private static String sheetName(String value) {
        if (value.matches("[\\p{L}\\p{N}_]+")) {
            return value;
        }
        return "'" + value.replace("'", "''") + "'";
    }

    private static String cellRange(String locator) {
        if (!locator.startsWith("'")) {
            int separator = locator.lastIndexOf('!');
            return separator < 0 ? locator : locator.substring(separator + 1);
        }
        for (int index = 1; index < locator.length(); index++) {
            if (locator.charAt(index) != '\'') {
                continue;
            }
            if (index + 1 < locator.length()
                    && locator.charAt(index + 1) == '\'') {
                index++;
                continue;
            }
            if (index + 1 < locator.length()
                    && locator.charAt(index + 1) == '!') {
                return locator.substring(index + 2);
            }
            break;
        }
        return locator;
    }

    private static Map<String, String> workbookStates(InputStream input)
            throws Exception {
        Map<String, String> states = new LinkedHashMap<>();
        XMLStreamReader xml = xmlFactory().createXMLStreamReader(input);
        try {
            while (xml.hasNext()) {
                if (xml.next() == XMLStreamConstants.START_ELEMENT
                        && "sheet".equals(xml.getLocalName())) {
                    String name = attribute(xml, "name");
                    String state = attribute(xml, "state");
                    if (name != null) {
                        states.put(name, state == null ? "visible" : state);
                    }
                }
            }
        } finally {
            xml.close();
        }
        return states;
    }

    private static SheetMetadata sheetMetadata(InputStream input)
            throws Exception {
        SheetMetadata result = new SheetMetadata();
        XMLStreamReader xml = xmlFactory().createXMLStreamReader(input);
        String currentCell = null;
        CellMetadata current = null;
        try {
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = xml.getLocalName();
                    if ("row".equals(name)
                            && truthy(attribute(xml, "hidden"))) {
                        String row = attribute(xml, "r");
                        if (row != null) {
                            result.hiddenRows.add(Integer.parseInt(row) - 1);
                        }
                    } else if ("col".equals(name)
                            && truthy(attribute(xml, "hidden"))) {
                        int start = Integer.parseInt(attribute(xml, "min")) - 1;
                        int end = Integer.parseInt(attribute(xml, "max")) - 1;
                        result.hiddenColumns.add(new IntRange(start, end));
                    } else if ("mergeCell".equals(name)) {
                        String reference = attribute(xml, "ref");
                        if (reference != null) {
                            CellRangeAddress range = CellRangeAddress.valueOf(reference);
                            result.addMerge(range);
                        }
                    } else if ("c".equals(name)) {
                        currentCell = attribute(xml, "r");
                        current = new CellMetadata(
                                attribute(xml, "t"),
                                integer(attribute(xml, "s"), -1),
                                null,
                                null
                        );
                    } else if ("f".equals(name) && current != null) {
                        current.formula = xml.getElementText();
                    } else if ("v".equals(name) && current != null) {
                        current.rawValue = xml.getElementText();
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT
                        && "c".equals(xml.getLocalName())
                        && currentCell != null && current != null) {
                    if (!result.cells.containsKey(currentCell)) {
                        requireRuntimeLimit(
                                result.cells.size() + 1L,
                                MAX_CELLS,
                                "SPREADSHEET_CELL_LIMIT",
                                "Spreadsheet contains too many cells"
                        );
                    }
                    result.characters += current.textLength();
                    requireRuntimeLimit(
                            result.characters,
                            MAX_TEXT_CHARACTERS,
                            "SPREADSHEET_TEXT_LIMIT",
                            "Spreadsheet contains too much cell metadata"
                    );
                    result.cells.put(currentCell, current);
                    if ("e".equals(current.type)) {
                        result.formulaErrors++;
                    }
                    currentCell = null;
                    current = null;
                }
            }
        } finally {
            xml.close();
        }
        return result;
    }

    private static XMLInputFactory xmlFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        setXmlProperty(factory, XMLInputFactory.SUPPORT_DTD, false);
        setXmlProperty(
                factory,
                "javax.xml.stream.isSupportingExternalEntities",
                false
        );
        return factory;
    }

    private static void setXmlProperty(
            XMLInputFactory factory,
            String property,
            Object value
    ) {
        try {
            factory.setProperty(property, value);
        } catch (IllegalArgumentException ignored) {
            // The package was already screened; unsupported hardening flags are
            // tolerated only for JDK StAX implementation differences.
        }
    }

    private static void applyMerges(
            NavigableMap<Integer, NavigableMap<Integer, CellValue>> rows,
            List<CellRangeAddress> merges,
            Set<Integer> hiddenRows,
            List<IntRange> hiddenColumns
    ) {
        for (CellRangeAddress merge : merges) {
            int firstRow = firstVisible(
                    merge.getFirstRow(),
                    merge.getLastRow(),
                    hiddenRows
            );
            int firstColumn = firstVisible(
                    merge.getFirstColumn(),
                    merge.getLastColumn(),
                    hiddenColumns
            );
            if (firstRow < 0 || firstColumn < 0) {
                continue;
            }
            NavigableMap<Integer, CellValue> row =
                    rows.computeIfAbsent(firstRow, ignored -> new TreeMap<>());
            CellValue top = row.computeIfAbsent(
                    firstColumn,
                    ignored -> CellValue.blank(firstRow, firstColumn)
            );
            top.rowSpan = visibleCount(
                    merge.getFirstRow(),
                    merge.getLastRow(),
                    hiddenRows
            );
            top.columnSpan = visibleCount(
                    merge.getFirstColumn(),
                    merge.getLastColumn(),
                    hiddenColumns
            );
            rows.subMap(
                    merge.getFirstRow(), true,
                    merge.getLastRow(), true
            ).forEach((rowIndex, existingRow) -> existingRow.subMap(
                    merge.getFirstColumn(), true,
                    merge.getLastColumn(), true
            ).forEach((column, covered) -> {
                if ((rowIndex != firstRow || column != firstColumn)
                        && !hiddenRows.contains(rowIndex)
                        && !hidden(column, hiddenColumns)) {
                    covered.coveredByMerge = true;
                }
            }));
        }
    }

    private static int firstVisible(
            int start,
            int end,
            Set<Integer> hidden
    ) {
        for (int value = start; value <= end; value++) {
            if (!hidden.contains(value)) {
                return value;
            }
        }
        return -1;
    }

    private static int firstVisible(
            int start,
            int end,
            List<IntRange> hidden
    ) {
        for (int value = start; value <= end; value++) {
            if (!hidden(value, hidden)) {
                return value;
            }
        }
        return -1;
    }

    private static int visibleCount(
            int start,
            int end,
            Set<Integer> hidden
    ) {
        int count = 0;
        for (int value = start; value <= end; value++) {
            count += hidden.contains(value) ? 0 : 1;
        }
        return Math.max(1, count);
    }

    private static int visibleCount(
            int start,
            int end,
            List<IntRange> hidden
    ) {
        int count = 0;
        for (int value = start; value <= end; value++) {
            count += hidden(value, hidden) ? 0 : 1;
        }
        return Math.max(1, count);
    }

    private static boolean hidden(int column, List<IntRange> values) {
        return values.stream().anyMatch(range ->
                column >= range.start && column <= range.end);
    }

    private static Encoding detectEncoding(Path path)
            throws IOException, ParserProcessingException {
        byte[] header = new byte[4];
        int length;
        try (InputStream input = Files.newInputStream(path)) {
            length = input.read(header);
        }
        if (length >= 3
                && (header[0] & 0xff) == 0xef
                && (header[1] & 0xff) == 0xbb
                && (header[2] & 0xff) == 0xbf) {
            return new Encoding(StandardCharsets.UTF_8, 3, "UTF-8");
        }
        if (length >= 2
                && (header[0] & 0xff) == 0xff
                && (header[1] & 0xff) == 0xfe) {
            return new Encoding(StandardCharsets.UTF_16LE, 2, "UTF-16LE");
        }
        if (length >= 2
                && (header[0] & 0xff) == 0xfe
                && (header[1] & 0xff) == 0xff) {
            return new Encoding(StandardCharsets.UTF_16BE, 2, "UTF-16BE");
        }
        if (decodable(path, StandardCharsets.UTF_8)) {
            return new Encoding(StandardCharsets.UTF_8, 0, "UTF-8");
        }
        if (decodable(path, GB18030)) {
            return new Encoding(GB18030, 0, "GB18030");
        }
        throw new ParserProcessingException(
                "CSV_ENCODING_INVALID",
                "CSV encoding is not valid UTF-8, BOM UTF-16 or GB18030"
        );
    }

    private static boolean decodable(Path path, Charset charset)
            throws IOException {
        try (Reader reader = reader(path, new Encoding(charset, 0, ""))) {
            char[] buffer = new char[8192];
            while (reader.read(buffer) >= 0) {
                // Validation only; parsing reopens the bounded file.
            }
            return true;
        } catch (CharacterCodingException exception) {
            return false;
        }
    }

    private static Reader reader(Path path, Encoding encoding)
            throws IOException {
        InputStream input = Files.newInputStream(path);
        input.skipNBytes(encoding.skipBytes);
        return new BufferedReader(new InputStreamReader(
                input,
                encoding.charset.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
        ));
    }

    private static char detectDelimiter(Path path, Encoding encoding)
            throws IOException {
        StringBuilder sample = new StringBuilder();
        try (Reader reader = reader(path, encoding)) {
            char[] buffer = new char[4096];
            while (sample.length() < CSV_SAMPLE_CHARACTERS) {
                int count = reader.read(
                        buffer,
                        0,
                        Math.min(
                                buffer.length,
                                CSV_SAMPLE_CHARACTERS - sample.length()
                        )
                );
                if (count < 0) {
                    break;
                }
                sample.append(buffer, 0, count);
            }
        }
        char[] candidates = {',', '\t', ';', '|'};
        char best = ',';
        int bestScore = -1;
        for (char candidate : candidates) {
            int score = delimiterScore(sample.toString(), candidate);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private static int delimiterScore(String sample, char delimiter) {
        int rows = 0;
        int total = 0;
        int minimum = Integer.MAX_VALUE;
        int maximum = 0;
        int current = 0;
        boolean quoted = false;
        for (int index = 0; index <= sample.length(); index++) {
            char value = index == sample.length() ? '\n' : sample.charAt(index);
            if (value == '"') {
                if (quoted && index + 1 < sample.length()
                        && sample.charAt(index + 1) == '"') {
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (!quoted && value == delimiter) {
                current++;
            } else if (!quoted && value == '\n') {
                if (current > 0) {
                    rows++;
                    total += current;
                    minimum = Math.min(minimum, current);
                    maximum = Math.max(maximum, current);
                }
                current = 0;
            }
        }
        return rows == 0 ? 0
                : rows * 100 + total - (maximum - minimum) * 20;
    }

    private static String delimiterLabel(Character value) {
        if (value == null) {
            return "NONE";
        }
        return switch (value) {
            case ',' -> "COMMA";
            case '\t' -> "TAB";
            case ';' -> "SEMICOLON";
            case '|' -> "PIPE";
            default -> "U+" + Integer.toHexString(value).toUpperCase(Locale.ROOT);
        };
    }

    private static String cellType(String raw) {
        if (raw == null) {
            return "NUMBER";
        }
        return switch (raw) {
            case "b" -> "BOOLEAN";
            case "e" -> "ERROR";
            case "s", "str", "inlineStr" -> "TEXT";
            default -> "NUMBER";
        };
    }

    private static boolean numeric(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            Double.parseDouble(value.replace(",", ""));
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String normalized(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(
                value.replace("\r\n", "\n").replace('\r', '\n'),
                Normalizer.Form.NFC
        ).strip();
    }

    private static String attribute(XMLStreamReader reader, String name) {
        for (int index = 0; index < reader.getAttributeCount(); index++) {
            if (name.equals(reader.getAttributeLocalName(index))) {
                return reader.getAttributeValue(index);
            }
        }
        return null;
    }

    private static int integer(String value, int fallback) {
        return value == null ? fallback : Integer.parseInt(value);
    }

    private static boolean truthy(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private static void requireProvider(
            DocumentFormat format,
            ParserProviderKind provider
    ) {
        boolean valid = format == DocumentFormat.XLSX
                && provider == ParserProviderKind.XLSX_POI
                || format == DocumentFormat.CSV
                && provider == ParserProviderKind.CSV_STREAM;
        if (!valid) {
            throw new IllegalArgumentException(
                    provider + " does not support " + format
            );
        }
    }

    private static void requireLimit(
            long actual,
            long maximum,
            String code,
            String message
    ) throws ParserProcessingException {
        if (actual > maximum) {
            throw new ParserProcessingException(code, message);
        }
    }

    private static void requireRuntimeLimit(
            long actual,
            long maximum,
            String code,
            String message
    ) {
        if (actual > maximum) {
            throw new SpreadsheetLimitException(code, message);
        }
    }

    private static SpreadsheetLimitException limitCause(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SpreadsheetLimitException limit) {
                return limit;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String json(ObjectNode value)
            throws ParserProcessingException {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ParserProcessingException(
                    "SPREADSHEET_MANIFEST_INVALID",
                    "Spreadsheet manifest could not be serialized",
                    exception
            );
        }
    }

    private static final class WorkbookDraft {
        private final ParserProviderKind provider;
        private final String encoding;
        private final Character delimiter;
        private final OoxmlPackageInspector.Inspection inspection;
        private final List<SheetDraft> sheets = new ArrayList<>();
        private int hiddenSheets;
        private int hiddenRows;
        private int hiddenColumns;
        private int emptySheets;
        private int emptyRows;
        private int formulaErrors;
        private int cells;
        private int reservedCells;
        private long characters;

        private WorkbookDraft(
                ParserProviderKind provider,
                String encoding,
                Character delimiter,
                OoxmlPackageInspector.Inspection inspection
        ) {
            this.provider = provider;
            this.encoding = encoding;
            this.delimiter = delimiter;
            this.inspection = inspection;
        }

        private void accept(SheetDraft sheet)
                throws ParserProcessingException {
            hiddenRows += sheet.hiddenRows;
            hiddenColumns += sheet.hiddenColumns;
            formulaErrors += sheet.formulaErrors;
            int sheetCells = sheet.rows.values().stream()
                    .mapToInt(Map::size)
                    .sum();
            cells += sheetCells;
            requireLimit(
                    cells,
                    MAX_CELLS,
                    "SPREADSHEET_CELL_LIMIT",
                    "Spreadsheet contains too many visible cells"
            );
            requireLimit(
                    sheet.rows.size(),
                    MAX_ROWS,
                    "SPREADSHEET_ROW_LIMIT",
                    "Spreadsheet contains too many visible rows"
            );
            if (sheet.rows.isEmpty()) {
                emptySheets++;
            } else {
                sheets.add(sheet);
            }
        }

        private void reserveCells(int count) throws ParserProcessingException {
            reservedCells += count;
            requireLimit(
                    reservedCells,
                    MAX_CELLS,
                    "SPREADSHEET_CELL_LIMIT",
                    "Spreadsheet contains too many visible cells"
            );
        }

        private void reserveCharacters(int count)
                throws ParserProcessingException {
            characters += count;
            requireLimit(
                    characters,
                    MAX_TEXT_CHARACTERS,
                    "SPREADSHEET_TEXT_LIMIT",
                    "Spreadsheet contains too much indexable text"
            );
        }

        private void requireContent() throws ParserProcessingException {
            if (sheets.isEmpty()) {
                throw new ParserProcessingException(
                        "SPREADSHEET_EMPTY",
                        "Spreadsheet contains no visible indexable cells"
                );
            }
        }
    }

    private record SheetDraft(
            String name,
            int order,
            NavigableMap<Integer, NavigableMap<Integer, CellValue>> rows,
            int hiddenRows,
            int hiddenColumns,
            int formulaErrors
    ) {
    }

    private static final class CellValue {
        private final String reference;
        private final int sourceRow;
        private final int sourceColumn;
        private final String text;
        private final String rawValue;
        private final String displayValue;
        private final String formulaText;
        private final String numberFormat;
        private final String cellType;
        private int rowSpan;
        private int columnSpan;
        private boolean coveredByMerge;

        private CellValue(
                String reference,
                int sourceRow,
                int sourceColumn,
                String text,
                String rawValue,
                String displayValue,
                String formulaText,
                String numberFormat,
                String cellType,
                int rowSpan,
                int columnSpan
        ) {
            this.reference = reference;
            this.sourceRow = sourceRow;
            this.sourceColumn = sourceColumn;
            this.text = text;
            this.rawValue = rawValue;
            this.displayValue = displayValue;
            this.formulaText = formulaText;
            this.numberFormat = numberFormat;
            this.cellType = cellType;
            this.rowSpan = rowSpan;
            this.columnSpan = columnSpan;
        }

        private static CellValue blank(int row, int column) {
            String reference = new CellReference(row, column).formatAsString();
            return new CellValue(
                    reference,
                    row,
                    column,
                    "",
                    "",
                    "",
                    null,
                    null,
                    "BLANK",
                    1,
                    1
            );
        }

        private String indexText() {
            String display = displayValue.isBlank() ? text : displayValue;
            return formulaText == null
                    ? display
                    : display + "（公式 " + formulaText + "）";
        }
    }

    private static final class SheetMetadata {
        private final Map<String, CellMetadata> cells = new HashMap<>();
        private final Set<Integer> hiddenRows = new HashSet<>();
        private final List<IntRange> hiddenColumns = new ArrayList<>();
        private final List<CellRangeAddress> merges = new ArrayList<>();
        private int formulaErrors;
        private long mergedArea;
        private long characters;

        private void addMerge(CellRangeAddress range) {
            requireRuntimeLimit(
                    merges.size() + 1L,
                    MAX_MERGED_REGIONS,
                    "SPREADSHEET_MERGE_LIMIT",
                    "Spreadsheet contains too many merged regions"
            );
            if (range.getFirstRow() < 0
                    || range.getLastRow() >= MAX_ROWS
                    || range.getFirstColumn() < 0
                    || range.getLastColumn() >= MAX_COLUMNS) {
                throw new SpreadsheetLimitException(
                        "SPREADSHEET_MERGE_LIMIT",
                        "Merged region exceeds the safe row or column limit"
                );
            }
            long area = (long) (range.getLastRow() - range.getFirstRow() + 1)
                    * (range.getLastColumn() - range.getFirstColumn() + 1L);
            mergedArea += area;
            requireRuntimeLimit(
                    mergedArea,
                    MAX_MERGED_AREA,
                    "SPREADSHEET_MERGE_LIMIT",
                    "Merged regions exceed the safe area limit"
            );
            merges.add(range);
        }
    }

    private static final class CellMetadata {
        private static final CellMetadata EMPTY =
                new CellMetadata(null, -1, null, null);
        private final String type;
        private final int styleIndex;
        private String rawValue;
        private String formula;

        private int textLength() {
            return (rawValue == null ? 0 : rawValue.length())
                    + (formula == null ? 0 : formula.length());
        }

        private CellMetadata(
                String type,
                int styleIndex,
                String rawValue,
                String formula
        ) {
            this.type = type;
            this.styleIndex = styleIndex;
            this.rawValue = rawValue;
            this.formula = formula;
        }
    }

    private static final class SpreadsheetLimitException
            extends RuntimeException {
        private final String code;

        private SpreadsheetLimitException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private record IntRange(int start, int end) {
    }

    private record Encoding(Charset charset, int skipBytes, String name) {
    }
}
