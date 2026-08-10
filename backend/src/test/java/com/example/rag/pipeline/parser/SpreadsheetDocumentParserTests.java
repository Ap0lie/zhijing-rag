package com.example.rag.pipeline.parser;

import com.example.rag.document.OoxmlPackageInspector;
import com.example.rag.persistence.DocumentFormat;
import com.example.rag.persistence.SourceLocatorKind;
import com.example.rag.persistence.SourceUnitKind;
import com.example.rag.pipeline.ParserInput;
import com.example.rag.pipeline.ParserProcessingException;
import com.example.rag.pipeline.ParserProviderKind;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpreadsheetDocumentParserTests {

    private static final UUID DOCUMENT_ID = UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
    );
    private static final UUID REVISION_ID = UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
    );
    private static final ChunkingProfile PROFILE =
            ChunkingProfile.phase4Default();

    private final SpreadsheetDocumentParser parser =
            new SpreadsheetDocumentParser(new OoxmlPackageInspector());

    @TempDir
    Path directory;

    @Test
    void streamsVisibleXlsxCellsAndPreservesFormulaCacheWithoutEvaluation()
            throws Exception {
        Path path = directory.resolve("sales.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("销售汇总");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("地区");
            header.createCell(1).setCellValue("收入");
            header.createCell(2).setCellValue("成本");
            header.createCell(3).setCellValue("利润");

            var shanghai = sheet.createRow(1);
            shanghai.createCell(0).setCellValue("上海");
            shanghai.createCell(1).setCellValue(120);
            shanghai.createCell(2).setCellValue(70);
            shanghai.createCell(3).setCellFormula("B2-C2");

            var beijing = sheet.createRow(2);
            beijing.createCell(0).setCellValue("北京");
            beijing.createCell(1).setCellValue(150);
            beijing.createCell(2).setCellValue(80);
            beijing.createCell(3).setCellFormula("B3-C3");

            var hidden = sheet.createRow(3);
            hidden.createCell(0).setCellValue("隐藏行不得索引");
            hidden.setZeroHeight(true);
            sheet.setColumnHidden(4, true);
            header.createCell(4).setCellValue("隐藏列");
            shanghai.createCell(4).setCellValue("秘密");

            var merged = sheet.createRow(4);
            merged.createCell(0).setCellValue("合并说明");
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(
                    4, 4, 0, 1
            ));
            workbook.getCreationHelper()
                    .createFormulaEvaluator()
                    .evaluateAll();

            var hiddenSheet = workbook.createSheet(" 内部 ");
            hiddenSheet.createRow(0)
                    .createCell(0)
                    .setCellValue("隐藏工作表不得索引");
            workbook.setSheetHidden(1, true);
            try (var output = Files.newOutputStream(path)) {
                workbook.write(output);
            }
        }

        ParsedDocument first = parse(
                path,
                DocumentFormat.XLSX,
                ParserProviderKind.XLSX_POI,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        );
        ParsedDocument second = parse(
                path,
                DocumentFormat.XLSX,
                ParserProviderKind.XLSX_POI,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        );

        assertThat(first.markdown())
                .contains("销售汇总", "上海", "50", "公式 =B2-C2")
                .doesNotContain("隐藏行不得索引", "隐藏工作表不得索引", "秘密");
        assertThat(first.sourceUnits())
                .extracting(ParsedDocument.SourceUnit::kind)
                .containsOnly(SourceUnitKind.SHEET);
        assertThat(first.contentBlocks())
                .allSatisfy(block -> assertThat(
                        block.sourceSpan().locatorKind()
                ).isEqualTo(SourceLocatorKind.CELL_RANGE));
        assertThat(first.tables())
                .flatExtracting(ParsedStructure.Table::cells)
                .anySatisfy(cell -> {
                    assertThat(cell.cellReference()).isEqualTo("D2");
                    assertThat(cell.cellType()).isEqualTo("FORMULA");
                    assertThat(cell.formulaText()).isEqualTo("=B2-C2");
                    assertThat(cell.displayValue()).isEqualTo("50");
                });
        assertThat(first.tables())
                .flatExtracting(ParsedStructure.Table::cells)
                .filteredOn(cell -> "A5".equals(cell.cellReference()))
                .singleElement()
                .satisfies(cell -> assertThat(cell.columnSpan()).isEqualTo(2));
        assertThat(first.packageMetadata().manifestJson())
                .contains("\"formulaEvaluationPerformed\":false")
                .contains("\"hiddenSheetsSkipped\":1");
        assertThat(first.packageMetadata().outputHash())
                .isEqualTo(second.packageMetadata().outputHash());
        assertThat(first.chunks().stream().map(ParsedDocument.Chunk::id))
                .containsExactlyElementsOf(
                        second.chunks().stream()
                                .map(ParsedDocument.Chunk::id)
                                .toList()
                );
    }

    @Test
    void sharedFormulaFollowerUsesCachedDisplayWithoutInventingFormula()
            throws Exception {
        Path path = directory.resolve("shared-formula.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("A!B");
            sheet.createRow(0).createCell(0).setCellValue("编号");
            var first = sheet.createRow(1);
            first.createCell(0).setCellValue("A");
            first.createCell(1).setCellValue(10);
            first.createCell(2).setCellValue(4);
            first.createCell(3).setCellFormula("B2-C2");
            var second = sheet.createRow(2);
            second.createCell(0).setCellValue("B");
            second.createCell(1).setCellValue(11);
            second.createCell(2).setCellValue(3);
            second.createCell(3).setCellFormula("B3-C3");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            try (var output = Files.newOutputStream(path)) {
                workbook.write(output);
            }
        }
        transformSheetXml(path, xml -> xml
                .replace(
                        "<f>B2-C2</f>",
                        "<f t=\"shared\" ref=\"D2:D3\" si=\"0\">B2-C2</f>"
                )
                .replace(
                        "<f>B3-C3</f>",
                        "<f t=\"shared\" si=\"0\"/>"
                ));

        ParsedDocument parsed = parse(
                path,
                DocumentFormat.XLSX,
                ParserProviderKind.XLSX_POI,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        );

        assertThat(parsed.tables()).singleElement().satisfies(table -> {
            assertThat(table.caption()).isEqualTo("A!B · A1:D1,A2:D3");
            assertThat(table.cells())
                    .filteredOn(cell -> "D3".equals(cell.cellReference()))
                    .singleElement()
                    .satisfies(cell -> {
                        assertThat(cell.displayValue()).isEqualTo("8");
                        assertThat(cell.formulaText()).isNull();
                    });
        });
    }

    @Test
    void streamsCsvWithDetectedDelimiterAndRepeatsHeadersPerRowWindow()
            throws Exception {
        Path path = directory.resolve("inventory.csv");
        StringBuilder csv = new StringBuilder("编号;名称;数量;备注\n");
        for (int index = 1; index <= 45; index++) {
            csv.append("SKU-").append(index)
                    .append(";物料 ").append(index)
                    .append(';').append(index * 2)
                    .append(';')
                    .append(index == 1 ? "=1+1" : "正常")
                    .append('\n');
        }
        Files.writeString(path, csv, StandardCharsets.UTF_8);

        ParsedDocument parsed = parse(
                path,
                DocumentFormat.CSV,
                ParserProviderKind.CSV_STREAM,
                "text/csv"
        );

        assertThat(parsed.tables()).hasSize(3);
        assertThat(parsed.tables()).allSatisfy(table ->
                assertThat(table.cells().stream()
                        .filter(ParsedStructure.Cell::header))
                        .hasSize(4));
        assertThat(parsed.tables().getFirst().cells())
                .filteredOn(cell -> "D2".equals(cell.cellReference()))
                .singleElement()
                .satisfies(cell -> {
                    assertThat(cell.text()).isEqualTo("=1+1");
                    assertThat(cell.formulaText()).isNull();
                    assertThat(cell.cellType()).isEqualTo("TEXT");
                });
        assertThat(parsed.contentBlocks())
                .allSatisfy(block -> assertThat(
                        block.sourceSpan().address()
                ).contains("!"));
        assertThat(parsed.packageMetadata().manifestJson())
                .contains("\"delimiter\":\"SEMICOLON\"")
                .contains("\"encoding\":\"UTF-8\"")
                .contains("\"formulaEvaluationPerformed\":false");
    }

    @Test
    void rejectsWorkbookWideMergedRegionBeforeExpandingCells() throws Exception {
        Path path = directory.resolve("oversized-merge.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("范围");
            sheet.createRow(0).createCell(0).setCellValue("value");
            try (var output = Files.newOutputStream(path)) {
                workbook.write(output);
            }
        }
        replaceSheetXml(
                path,
                "<mergeCells count=\"1\"><mergeCell ref=\"A1:XFD1000\"/>"
                        + "</mergeCells>"
        );

        assertThatThrownBy(() -> parse(
                path,
                DocumentFormat.XLSX,
                ParserProviderKind.XLSX_POI,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )).isInstanceOfSatisfying(
                ParserProcessingException.class,
                failure -> assertThat(failure.code())
                        .isEqualTo("SPREADSHEET_MERGE_LIMIT")
        );
    }

    @Test
    void rejectsCsvCellBudgetBeforeMaterializingTheWholeFile() throws Exception {
        Path path = directory.resolve("too-many-cells.csv");
        String row = String.join(",", java.util.Collections.nCopies(128, "x"));
        StringBuilder content = new StringBuilder();
        for (int index = 0; index < 782; index++) {
            content.append(row).append('\n');
        }
        Files.writeString(path, content, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> parse(
                path,
                DocumentFormat.CSV,
                ParserProviderKind.CSV_STREAM,
                "text/csv"
        )).isInstanceOfSatisfying(
                ParserProcessingException.class,
                failure -> assertThat(failure.code())
                        .isEqualTo("SPREADSHEET_CELL_LIMIT")
        );
    }

    private ParsedDocument parse(
            Path path,
            DocumentFormat format,
            ParserProviderKind provider,
            String mediaType
    ) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        return parser.parse(
                new ParserInput(
                        path,
                        DOCUMENT_ID,
                        REVISION_ID,
                        format,
                        mediaType,
                        bytes.length,
                        HexFormat.of().formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(bytes)
                        )
                ),
                PROFILE,
                provider
        );
    }

    private void replaceSheetXml(Path workbook, String addition)
            throws Exception {
        transformSheetXml(workbook, xml -> xml.replace(
                "</worksheet>", addition + "</worksheet>"
        ));
    }

    private void transformSheetXml(
            Path workbook,
            UnaryOperator<String> transformation
    ) throws Exception {
        Path replacement = directory.resolve("replacement.xlsx");
        try (ZipInputStream input = new ZipInputStream(
                Files.newInputStream(workbook)
        ); ZipOutputStream output = new ZipOutputStream(
                Files.newOutputStream(replacement)
        )) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                ZipEntry copy = new ZipEntry(entry.getName());
                output.putNextEntry(copy);
                byte[] bytes = input.readAllBytes();
                if ("xl/worksheets/sheet1.xml".equals(entry.getName())) {
                    String xml = new String(bytes, StandardCharsets.UTF_8);
                    bytes = transformation.apply(xml)
                            .getBytes(StandardCharsets.UTF_8);
                }
                output.write(bytes);
                output.closeEntry();
            }
        }
        Files.move(
                replacement,
                workbook,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
        );
    }
}
