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
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ooxml.POIXMLDocumentPart;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.BodyElementType;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFootnote;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Node;

/**
 * Deterministic native DOCX/PPTX parser. POI reads only a package that already
 * passed the shared bounded OOXML inspection.
 */
@Component
public final class OfficeDocumentParser {

    public static final String POI_VERSION = "5.5.1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern HEADING_STYLE =
            Pattern.compile("(?i)(?:heading|标题)\\s*([1-6])");
    private static final int MAX_PARAGRAPHS = 20_000;
    private static final int MAX_SLIDES = 1_000;
    private static final int MAX_SHAPES = 20_000;
    private static final int MAX_SOURCE_UNITS = 150_000;
    private static final int MAX_FOOTNOTE_PARAGRAPHS = 10_000;
    private static final int MAX_IMAGE_REFERENCES = 2_000;
    private static final int MAX_TABLE_CELLS = 100_000;
    private static final int MAX_TEXT_CHARACTERS = 10_000_000;

    static {
        ZipSecureFile.setMinInflateRatio(
                OoxmlPackageInspector.MIN_INFLATE_RATIO
        );
        ZipSecureFile.setMaxEntrySize(
                OoxmlPackageInspector.MAX_ENTRY_BYTES
        );
        ZipSecureFile.setMaxTextSize(MAX_TEXT_CHARACTERS);
    }

    private final OoxmlPackageInspector inspector;

    public OfficeDocumentParser(OoxmlPackageInspector inspector) {
        this.inspector = inspector;
    }

    public ParsedDocument parse(
            ParserInput input,
            ChunkingProfile profile,
            ParserProviderKind provider
    ) throws IOException, ParserProcessingException {
        requireProvider(input.documentFormat(), provider);
        Instant startedAt = Instant.now();
        OoxmlPackageInspector.Inspection inspection;
        try {
            inspection = inspector.inspect(input.path(), input.documentFormat());
        } catch (OoxmlValidationException exception) {
            throw new ParserProcessingException(
                    exception.code(),
                    exception.getMessage(),
                    exception
            );
        }
        Draft draft = new Draft(input, provider, inspection);
        try {
            if (input.documentFormat() == DocumentFormat.DOCX) {
                parseDocx(input, draft);
            } else {
                parsePptx(input, draft);
            }
        } catch (ParserProcessingException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ParserProcessingException(
                    "OFFICE_PARSE_FAILED",
                    "Office document could not be parsed safely",
                    exception
            );
        }
        return assemble(draft, profile, startedAt);
    }

    private static void parseDocx(ParserInput input, Draft draft)
            throws Exception {
        try (OPCPackage pkg = OPCPackage.open(
                input.path().toFile(),
                PackageAccess.READ
        ); XWPFDocument document = new XWPFDocument(pkg)) {
            if (document.getComments() != null) {
                draft.commentsSkipped += document.getComments().length;
            }
            int paragraph = 0;
            int table = 0;
            for (IBodyElement element : document.getBodyElements()) {
                if (element.getElementType() == BodyElementType.PARAGRAPH) {
                    paragraph++;
                    requireLimit(
                            paragraph,
                            MAX_PARAGRAPHS,
                            "OFFICE_PARAGRAPH_LIMIT",
                            "Word document contains too many paragraphs"
                    );
                    addDocxParagraph(
                            (XWPFParagraph) element,
                            paragraph,
                            false,
                            draft
                    );
                } else if (element.getElementType() == BodyElementType.TABLE) {
                    table++;
                    addDocxTable(
                            (XWPFTable) element,
                            "docx:table:" + table,
                            "表格 " + table,
                            draft
                    );
                }
            }
            int footnote = 0;
            for (XWPFFootnote note : document.getFootnotes()) {
                footnote++;
                int noteParagraph = 0;
                for (XWPFParagraph paragraphValue : note.getParagraphs()) {
                    noteParagraph++;
                    draft.footnoteParagraphs++;
                    requireLimit(
                            draft.footnoteParagraphs,
                            MAX_FOOTNOTE_PARAGRAPHS,
                            "OFFICE_FOOTNOTE_PARAGRAPH_LIMIT",
                            "Word document contains too many footnote paragraphs"
                    );
                    addDocxParagraph(
                            paragraphValue,
                            noteParagraph,
                            true,
                            draft.withFootnote(footnote)
                    );
                }
            }
        }
    }

    private static void addDocxParagraph(
            XWPFParagraph paragraph,
            int paragraphNumber,
            boolean footnote,
            Draft draft
    ) throws ParserProcessingException {
        draft.deletedRevisionsSkipped += countElements(
                paragraph.getCTP().getDomNode(),
                "del"
        );
        String text = normalized(paragraph.getText());
        List<XWPFPicture> pictures = paragraph.getRuns().stream()
                .flatMap(run -> run.getEmbeddedPictures().stream())
                .toList();
        if (text.isBlank() && pictures.isEmpty()) {
            draft.skippedEmpty++;
            return;
        }
        int heading = footnote ? 0 : headingLevel(paragraph.getStyle());
        ParsedDocument.BlockType type = heading > 0
                ? ParsedDocument.BlockType.HEADING
                : paragraph.getNumID() == null
                ? ParsedDocument.BlockType.PARAGRAPH
                : ParsedDocument.BlockType.LIST;
        String address = footnote
                ? "docx:footnote:" + draft.footnote + ":paragraph:" + paragraphNumber
                : "docx:paragraph:" + paragraphNumber;
        String label = footnote
                ? "脚注 " + draft.footnote + " · 段落 " + paragraphNumber
                : heading > 0
                ? text
                : "段落 " + paragraphNumber;
        String unitText = text.isBlank() ? "图片 " + paragraphNumber : text;
        int unit = draft.addUnit(
                SourceUnitKind.PARAGRAPH,
                address,
                unitText,
                label,
                null
        );
        Integer block = text.isBlank()
                ? null
                : draft.addBlock(
                        unit,
                        type,
                        text,
                        markdown(type, text, heading),
                        heading
                );
        int pictureNumber = 0;
        for (XWPFPicture picture : pictures) {
            XWPFPictureData data = picture.getPictureData();
            if (data == null) {
                continue;
            }
            pictureNumber++;
            draft.addImage(
                    unit,
                    block,
                    "docx-" + (footnote ? "footnote-" + draft.footnote + "-" : "")
                            + "p" + paragraphNumber + "-image-"
                            + pictureNumber + "."
                            + safeExtension(data.suggestFileExtension()),
                    mediaType(data.suggestFileExtension()),
                    mediaPart(data),
                    data::getData,
                    picture.getDescription(),
                    null
            );
        }
    }

    private static void addDocxTable(
            XWPFTable table,
            String tableAddress,
            String tableLabel,
            Draft draft
    ) throws ParserProcessingException {
        List<ParsedStructure.Cell> cells = new ArrayList<>();
        List<List<String>> markdownRows = new ArrayList<>();
        List<NestedDocxTable> nestedTables = new ArrayList<>();
        int firstBlock = -1;
        int firstUnit = -1;
        int rowIndex = 0;
        for (XWPFTableRow row : table.getRows()) {
            List<String> markdownCells = new ArrayList<>();
            int columnIndex = 0;
            for (XWPFTableCell cell : row.getTableCells()) {
                draft.tableCells++;
                requireLimit(
                        draft.tableCells,
                        MAX_TABLE_CELLS,
                        "OFFICE_TABLE_CELL_LIMIT",
                        "Office document contains too many table cells"
                );
                cell.getParagraphs().forEach(paragraph ->
                        draft.deletedRevisionsSkipped += countElements(
                                paragraph.getCTP().getDomNode(),
                                "del"
                        ));
                String text = normalized(cell.getParagraphs().stream()
                        .map(XWPFParagraph::getText)
                        .reduce((left, right) -> left + "\n" + right)
                        .orElse(""));
                List<XWPFPicture> pictures = cell.getParagraphs().stream()
                        .flatMap(paragraph -> paragraph.getRuns().stream())
                        .flatMap(run -> run.getEmbeddedPictures().stream())
                        .toList();
                markdownCells.add(text);
                cells.add(new ParsedStructure.Cell(
                        FormatNeutralChunker.stableUuid(
                                "docx-cell",
                                draft.input.revisionId(),
                                tableAddress,
                                rowIndex,
                                columnIndex
                        ),
                        rowIndex,
                        columnIndex,
                        1,
                        1,
                        rowIndex == 0,
                        text,
                        sha256(text)
                ));
                if (!text.isBlank() || !pictures.isEmpty()) {
                    String address = tableAddress + ":cell:"
                            + rowIndex + ":" + columnIndex;
                    String unitText = text.isBlank()
                            ? "表格图片 R" + (rowIndex + 1)
                            + "C" + (columnIndex + 1)
                            : text;
                    int unit = draft.addUnit(
                            SourceUnitKind.TABLE_CELL,
                            address,
                            unitText,
                            tableLabel + " · R"
                                    + (rowIndex + 1) + "C" + (columnIndex + 1),
                            null
                    );
                    Integer block = null;
                    if (!text.isBlank()) {
                        block = draft.addBlock(
                                unit,
                                ParsedDocument.BlockType.TABLE,
                                text,
                                "",
                                0
                        );
                        if (firstBlock < 0) {
                            firstBlock = block;
                            firstUnit = unit;
                        }
                    }
                    int pictureNumber = 0;
                    for (XWPFPicture picture : pictures) {
                        XWPFPictureData data = picture.getPictureData();
                        if (data == null) {
                            continue;
                        }
                        pictureNumber++;
                        draft.addImage(
                                unit,
                                block,
                                address.replace(':', '-') + "-image-"
                                        + pictureNumber + "."
                                        + safeExtension(data.suggestFileExtension()),
                                mediaType(data.suggestFileExtension()),
                                mediaPart(data),
                                data::getData,
                                picture.getDescription(),
                                null
                        );
                    }
                }
                int nestedNumber = 0;
                for (XWPFTable nested : cell.getTables()) {
                    nestedNumber++;
                    nestedTables.add(new NestedDocxTable(
                            nested,
                            tableAddress + ":cell:" + rowIndex + ":"
                                    + columnIndex + ":table:" + nestedNumber,
                            tableLabel + " · R" + (rowIndex + 1)
                                    + "C" + (columnIndex + 1)
                                    + " · 嵌套表格 " + nestedNumber
                    ));
                }
                columnIndex++;
            }
            markdownRows.add(markdownCells);
            rowIndex++;
        }
        if (firstBlock < 0) {
            draft.skippedEmpty++;
            for (NestedDocxTable nested : nestedTables) {
                addDocxTable(
                        nested.table(),
                        nested.address(),
                        nested.label(),
                        draft
                );
            }
            return;
        }
        draft.blocks.get(firstBlock).markdown = tableMarkdown(markdownRows);
        draft.addTable(
                firstUnit,
                firstBlock,
                tableLabel,
                cells,
                tableHtml(cells)
        );
        for (NestedDocxTable nested : nestedTables) {
            addDocxTable(
                    nested.table(),
                    nested.address(),
                    nested.label(),
                    draft
            );
        }
    }

    private static void parsePptx(ParserInput input, Draft draft)
            throws Exception {
        try (OPCPackage pkg = OPCPackage.open(
                input.path().toFile(),
                PackageAccess.READ
        ); XMLSlideShow show = new XMLSlideShow(pkg)) {
            Dimension pageSize = show.getPageSize();
            List<XSLFSlide> slides = show.getSlides();
            requireLimit(
                    slides.size(),
                    MAX_SLIDES,
                    "OFFICE_SLIDE_LIMIT",
                    "Presentation contains too many slides"
            );
            for (int index = 0; index < slides.size(); index++) {
                XSLFSlide slide = slides.get(index);
                int slideNumber = index + 1;
                if (slide.getXmlObject().isSetShow()
                        && !slide.getXmlObject().getShow()) {
                    draft.hiddenSlides++;
                    continue;
                }
                draft.addUnit(
                        SourceUnitKind.SLIDE,
                        "pptx:slide:" + slideNumber,
                        "",
                        "幻灯片 " + slideNumber,
                        null
                );
                int firstBlock = draft.blocks.size();
                addSlideShapes(
                        slide.getShapes(),
                        slideNumber,
                        pageSize,
                        "shape",
                        draft
                );
                addNotes(slide, slideNumber, draft);
                if (draft.blocks.size() > firstBlock) {
                    draft.parentBreakBeforeBlocks.add(firstBlock);
                }
            }
        }
    }

    private static void addSlideShapes(
            List<XSLFShape> shapes,
            int slideNumber,
            Dimension pageSize,
            String prefix,
            Draft draft
    ) throws ParserProcessingException {
        for (int index = 0; index < shapes.size(); index++) {
            XSLFShape shape = shapes.get(index);
            draft.shapes++;
            requireLimit(
                    draft.shapes,
                    MAX_SHAPES,
                    "OFFICE_SHAPE_LIMIT",
                    "Presentation contains too many shapes"
            );
            String position = prefix + ":" + (index + 1);
            if (shape instanceof XSLFGroupShape group) {
                addSlideShapes(
                        group.getShapes(),
                        slideNumber,
                        pageSize,
                        position,
                        draft
                );
            } else if (shape instanceof XSLFTable table) {
                addPptxTable(
                        table,
                        slideNumber,
                        position,
                        pageSize,
                        draft
                );
            } else if (shape instanceof XSLFPictureShape picture) {
                addPptxImage(
                        picture,
                        slideNumber,
                        position,
                        pageSize,
                        draft
                );
            } else if (shape instanceof XSLFTextShape textShape) {
                addPptxText(
                        textShape,
                        slideNumber,
                        position,
                        pageSize,
                        draft
                );
            } else {
                draft.skippedShapes++;
            }
        }
    }

    private static void addPptxText(
            XSLFTextShape shape,
            int slideNumber,
            String position,
            Dimension pageSize,
            Draft draft
    ) throws ParserProcessingException {
        boolean title = shape.getTextType() == Placeholder.TITLE
                || shape.getTextType() == Placeholder.CENTERED_TITLE;
        String name = safeShapeName(shape.getShapeName(), position);
        List<XSLFTextParagraph> paragraphs = shape.getTextParagraphs();
        for (int index = 0; index < paragraphs.size(); index++) {
            XSLFTextParagraph paragraph = paragraphs.get(index);
            String text = normalized(paragraph.getText());
            if (text.isBlank()) {
                continue;
            }
            boolean heading = title && index == 0;
            boolean list = !heading && paragraph.isBullet();
            ParsedDocument.BlockType type = heading
                    ? ParsedDocument.BlockType.HEADING
                    : list
                    ? ParsedDocument.BlockType.LIST
                    : ParsedDocument.BlockType.PARAGRAPH;
            String address = "pptx:slide:" + slideNumber + ":"
                    + position + ":paragraph:" + (index + 1);
            int unit = draft.addUnit(
                    SourceUnitKind.SHAPE,
                    address,
                    text,
                    "幻灯片 " + slideNumber + " · " + name
                            + " · 段落 " + (index + 1),
                    normalizedBox(shape.getAnchor(), pageSize)
            );
            draft.addBlock(
                    unit,
                    type,
                    text,
                    heading ? "# " + text : list ? "- " + text : text,
                    heading ? 1 : 0
            );
        }
    }

    private static void addPptxTable(
            XSLFTable table,
            int slideNumber,
            String position,
            Dimension pageSize,
            Draft draft
    ) throws ParserProcessingException {
        List<ParsedStructure.Cell> cells = new ArrayList<>();
        List<List<String>> markdownRows = new ArrayList<>();
        int firstBlock = -1;
        int firstUnit = -1;
        int rowIndex = 0;
        for (XSLFTableRow row : table.getRows()) {
            List<String> markdownCells = new ArrayList<>();
            int columnIndex = 0;
            for (XSLFTableCell cell : row.getCells()) {
                draft.tableCells++;
                requireLimit(
                        draft.tableCells,
                        MAX_TABLE_CELLS,
                        "OFFICE_TABLE_CELL_LIMIT",
                        "Office document contains too many table cells"
                );
                String text = normalized(cell.getText());
                markdownCells.add(text);
                int rowSpan = Math.max(1, cell.getRowSpan());
                int columnSpan = Math.max(1, cell.getGridSpan());
                cells.add(new ParsedStructure.Cell(
                        FormatNeutralChunker.stableUuid(
                                "pptx-cell",
                                draft.input.revisionId(),
                                slideNumber,
                                position,
                                rowIndex,
                                columnIndex
                        ),
                        rowIndex,
                        columnIndex,
                        rowSpan,
                        columnSpan,
                        rowIndex == 0,
                        text,
                        sha256(text)
                ));
                if (!text.isBlank()) {
                    String address = "pptx:slide:" + slideNumber + ":"
                            + position + ":cell:" + rowIndex + ":"
                            + columnIndex;
                    int unit = draft.addUnit(
                            SourceUnitKind.TABLE_CELL,
                            address,
                            text,
                            "幻灯片 " + slideNumber + " · 表格 R"
                                    + (rowIndex + 1) + "C" + (columnIndex + 1),
                            normalizedBox(table.getAnchor(), pageSize)
                    );
                    int block = draft.addBlock(
                            unit,
                            ParsedDocument.BlockType.TABLE,
                            text,
                            "",
                            0
                    );
                    if (firstBlock < 0) {
                        firstBlock = block;
                        firstUnit = unit;
                    }
                }
                columnIndex += columnSpan;
            }
            markdownRows.add(markdownCells);
            rowIndex++;
        }
        if (firstBlock < 0) {
            return;
        }
        draft.blocks.get(firstBlock).markdown = tableMarkdown(markdownRows);
        draft.addTable(
                firstUnit,
                firstBlock,
                "幻灯片 " + slideNumber + " · 表格",
                cells,
                tableHtml(cells)
        );
    }

    private static void addPptxImage(
            XSLFPictureShape picture,
            int slideNumber,
            String position,
            Dimension pageSize,
            Draft draft
    ) throws ParserProcessingException {
        XSLFPictureData data = picture.getPictureData();
        if (data == null) {
            return;
        }
        String name = safeShapeName(picture.getShapeName(), position);
        String address = "pptx:slide:" + slideNumber + ":" + position;
        int unit = draft.addUnit(
                SourceUnitKind.SHAPE,
                address,
                name,
                "幻灯片 " + slideNumber + " · " + name,
                normalizedBox(picture.getAnchor(), pageSize)
        );
        draft.addImage(
                unit,
                null,
                "pptx-slide-" + slideNumber + "-" + position.replace(':', '-')
                        + "." + safeExtension(data.suggestFileExtension()),
                mediaType(data.suggestFileExtension()),
                mediaPart(data),
                data::getData,
                name,
                normalizedBox(picture.getAnchor(), pageSize)
        );
    }

    private static void addNotes(
            XSLFSlide slide,
            int slideNumber,
            Draft draft
    ) throws ParserProcessingException {
        XSLFNotes notes = slide.getNotes();
        if (notes == null) {
            return;
        }
        int index = 0;
        for (XSLFShape shape : notes.getShapes()) {
            if (!(shape instanceof XSLFTextShape textShape)
                    || textShape.getTextType() == Placeholder.SLIDE_NUMBER
                    || textShape.getTextType() == Placeholder.DATETIME
                    || textShape.getTextType() == Placeholder.FOOTER
                    || textShape.getTextType() == Placeholder.HEADER) {
                continue;
            }
            String text = normalized(textShape.getText());
            if (text.isBlank()) {
                continue;
            }
            index++;
            int unit = draft.addUnit(
                    SourceUnitKind.NOTES,
                    "pptx:slide:" + slideNumber + ":notes:" + index,
                    text,
                    "幻灯片 " + slideNumber + " · 备注 " + index,
                    null
            );
            draft.addBlock(
                    unit,
                    ParsedDocument.BlockType.PARAGRAPH,
                    text,
                    "> 备注：" + text.replace("\n", "\n> "),
                    0
            );
        }
    }

    private static ParsedDocument assemble(
            Draft draft,
            ChunkingProfile profile,
            Instant startedAt
    ) throws ParserProcessingException {
        if (draft.blocks.isEmpty()) {
            throw new ParserProcessingException(
                    "DOCUMENT_TEXT_EMPTY",
                    "Office document contains no indexable text"
            );
        }
        List<ParsedDocument.SourceUnit> units = new ArrayList<>();
        String normalization = draft.input.documentFormat().name()
                .toLowerCase(Locale.ROOT) + "-poi-" + POI_VERSION + "-nfc-v2";
        for (int index = 0; index < draft.units.size(); index++) {
            UnitDraft unit = draft.units.get(index);
            String hash = sha256(unit.text);
            units.add(new ParsedDocument.SourceUnit(
                    FormatNeutralChunker.stableUuid(
                            "source-unit",
                            draft.input.revisionId(),
                            unit.kind,
                            unit.address,
                            hash
                    ),
                    index + 1,
                    unit.kind,
                    unit.address,
                    unit.text,
                    hash,
                    normalization,
                    unitMetadata(unit)
            ));
        }

        List<ParsedDocument.ContentBlock> blocks = new ArrayList<>();
        List<String> headingPath = new ArrayList<>();
        for (int index = 0; index < draft.blocks.size(); index++) {
            BlockDraft value = draft.blocks.get(index);
            ParsedDocument.SourceUnit unit = units.get(value.unit);
            if (draft.parentBreakBeforeBlocks.contains(index)) {
                headingPath.clear();
            }
            if (value.type == ParsedDocument.BlockType.HEADING) {
                int depth = Math.max(1, Math.min(6, value.headingLevel));
                while (headingPath.size() >= depth) {
                    headingPath.removeLast();
                }
                headingPath.add(value.text);
            }
            List<ParsedStructure.BoundingBox> boxes = draft.units.get(value.unit).box == null
                    ? List.of()
                    : List.of(box(unit, draft.units.get(value.unit).box));
            ParsedDocument.SourceSpan span = new ParsedDocument.SourceSpan(
                    unit.id(),
                    unit.id(),
                    unit.order(),
                    unit.order(),
                    locatorKind(unit.kind()),
                    unit.stableAddress() + "#chars=0-" + value.text.length(),
                    0,
                    value.text.length(),
                    0,
                    value.text.length(),
                    sha256(value.text),
                    normalization,
                    boxes
            );
            blocks.add(new ParsedDocument.ContentBlock(
                    FormatNeutralChunker.stableUuid(
                            "block",
                            draft.input.revisionId(),
                            index,
                            unit.id(),
                            span.sourceTextHash()
                    ),
                    index,
                    value.type,
                    value.text,
                    List.copyOf(headingPath),
                    value.text.length(),
                    FormatNeutralChunker.countTokens(value.text),
                    profile.tokenCounterVersion(),
                    span
            ));
        }

        List<ParsedStructure.Image> images = new ArrayList<>();
        for (int index = 0; index < draft.images.size(); index++) {
            ImageDraft value = draft.images.get(index);
            ParsedDocument.SourceUnit unit = units.get(value.unit);
            NormalizedBox raw = value.box == null
                    ? new NormalizedBox(0, 0, 1000, 1000)
                    : value.box;
            images.add(new ParsedStructure.Image(
                    FormatNeutralChunker.stableUuid(
                            "office-image",
                            draft.input.revisionId(),
                            value.name,
                            sha256(value.bytes)
                    ),
                    index,
                    value.block,
                    ParsedStructure.AssetType.FIGURE,
                    box(unit, raw),
                    value.name,
                    value.mediaType,
                    value.bytes,
                    sha256(value.bytes),
                    value.caption
            ));
        }

        List<ParsedStructure.Table> tables = new ArrayList<>();
        for (int index = 0; index < draft.tables.size(); index++) {
            TableDraft value = draft.tables.get(index);
            ParsedDocument.SourceUnit unit = units.get(value.unit);
            NormalizedBox raw = draft.units.get(value.unit).box == null
                    ? new NormalizedBox(0, 0, 1000, 1000)
                    : draft.units.get(value.unit).box;
            tables.add(new ParsedStructure.Table(
                    FormatNeutralChunker.stableUuid(
                            "office-table",
                            draft.input.revisionId(),
                            index,
                            unit.stableAddress()
                    ),
                    index,
                    value.block,
                    null,
                    box(unit, raw),
                    value.caption,
                    value.html,
                    sha256(blocks.get(value.block).text()),
                    value.cells
            ));
        }

        String markdown = draft.blocks.stream()
                .map(block -> block.markdown)
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElseThrow() + "\n";
        String parserVersion = draft.provider == ParserProviderKind.DOCX_POI
                ? "docx-poi-" + POI_VERSION + "-v3"
                : "pptx-poi-" + POI_VERSION + "-v3";
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("schema", "office-native-v3");
        manifest.put("parser", draft.provider.name());
        manifest.put("parserVersion", parserVersion);
        manifest.put("parserRevision", "apache-poi-" + POI_VERSION);
        manifest.put("inputHash", draft.input.inputHash());
        manifest.put("outputHash", sha256(markdown));
        manifest.put("networkResourcesFetched", 0);
        manifest.put("activeContentExecuted", false);
        manifest.put("zipEntryCount", draft.inspection.entryCount());
        manifest.put("expandedBytes", draft.inspection.expandedBytes());
        manifest.put("imageCount", draft.images.size());
        manifest.put("imageReferences", draft.imageReferences);
        manifest.put("duplicateImageReferencesSkipped", draft.duplicateImages);
        manifest.put("emittedImageBytes", draft.emittedImageBytes);
        manifest.put("shapeCount", draft.shapes);
        manifest.put("footnoteParagraphCount", draft.footnoteParagraphs);
        manifest.put("commentPartsSkipped", draft.inspection.commentPartCount());
        manifest.put("commentsSkipped", draft.commentsSkipped);
        manifest.put("deletedRevisionsSkipped", draft.deletedRevisionsSkipped);
        manifest.put("hiddenSlidesSkipped", draft.hiddenSlides);
        manifest.put("unsupportedShapesSkipped", draft.skippedShapes);
        manifest.put("emptyBlocksSkipped", draft.skippedEmpty);
        manifest.put(
                "sanitization",
                "external relationships, macros, OLE and embedded packages rejected; comments, deleted revisions, hidden slides and unsupported active content skipped"
        );
        ParsedStructure.PackageMetadata metadata =
                new ParsedStructure.PackageMetadata(
                        draft.provider.name(),
                        parserVersion,
                        "apache-poi-" + POI_VERSION,
                        draft.input.inputHash(),
                        sha256(markdown),
                        "office-native-v3",
                        json(manifest)
                );
        List<ParsedDocument.Chunk> chunks =
                FormatNeutralChunker.createChunks(
                        draft.input.revisionId(),
                        blocks,
                        profile,
                        draft.parentBreakBeforeBlocks
                );
        int characters = blocks.stream()
                .mapToInt(ParsedDocument.ContentBlock::characterCount)
                .sum();
        requireLimit(
                characters,
                MAX_TEXT_CHARACTERS,
                "OFFICE_TEXT_LIMIT",
                "Office document contains too much extracted text"
        );
        int tokens = blocks.stream()
                .mapToInt(ParsedDocument.ContentBlock::tokenCount)
                .sum();
        return ParsedPackageIntegrity.seal(new ParsedDocument(
                draft.input.documentId(),
                draft.input.revisionId(),
                draft.input.documentFormat(),
                draft.provider,
                units,
                markdown,
                blocks,
                chunks,
                metadata,
                tables,
                images,
                characters,
                tokens,
                profile.version(),
                parserVersion,
                profile.chunkerVersion(),
                profile.tokenCounterVersion(),
                Duration.between(startedAt, Instant.now()).toMillis()
        ));
    }

    private static SourceLocatorKind locatorKind(SourceUnitKind kind) {
        return switch (kind) {
            case PARAGRAPH -> SourceLocatorKind.PARAGRAPH;
            case TABLE_CELL -> SourceLocatorKind.TABLE_CELL;
            case SLIDE, SHAPE, NOTES -> SourceLocatorKind.SLIDE_SHAPE;
            default -> throw new IllegalArgumentException(
                    "Unsupported Office SourceUnit kind " + kind
            );
        };
    }

    private static int headingLevel(String style) {
        if (style == null) {
            return 0;
        }
        Matcher matcher = HEADING_STYLE.matcher(style);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static String markdown(
            ParsedDocument.BlockType type,
            String text,
            int headingLevel
    ) {
        return switch (type) {
            case HEADING -> "#".repeat(Math.max(1, headingLevel)) + " " + text;
            case LIST -> "- " + text;
            default -> text;
        };
    }

    private static String tableMarkdown(List<List<String>> rows) {
        if (rows.isEmpty()) {
            return "";
        }
        int columns = rows.stream().mapToInt(List::size).max().orElse(1);
        StringBuilder value = new StringBuilder();
        appendMarkdownRow(value, rows.getFirst(), columns);
        value.append('\n');
        appendMarkdownRow(value, java.util.Collections.nCopies(columns, "---"), columns);
        for (int index = 1; index < rows.size(); index++) {
            value.append('\n');
            appendMarkdownRow(value, rows.get(index), columns);
        }
        return value.toString();
    }

    private static void appendMarkdownRow(
            StringBuilder target,
            List<String> row,
            int columns
    ) {
        target.append('|');
        for (int column = 0; column < columns; column++) {
            String text = column < row.size() ? row.get(column) : "";
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
            html.append('>').append(escapeHtml(cell.text()))
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

    private static String normalized(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(
                value.replace("\r\n", "\n").replace('\r', '\n'),
                Normalizer.Form.NFC
        ).strip();
    }

    private static String safeShapeName(String value, String fallback) {
        String normalized = normalized(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    private static String safeExtension(String value) {
        String extension = value == null
                ? "bin"
                : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return extension.isBlank() ? "bin" : extension;
    }

    private static String mediaType(String extension) {
        return switch (safeExtension(extension)) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp", "dib" -> "image/bmp";
            case "tif", "tiff" -> "image/tiff";
            default -> "application/octet-stream";
        };
    }

    private static String mediaPart(POIXMLDocumentPart data) {
        return data.getPackagePart().getPartName().getName();
    }

    private static NormalizedBox normalizedBox(
            Rectangle2D anchor,
            Dimension page
    ) {
        if (anchor == null || page == null
                || page.width <= 0 || page.height <= 0) {
            return new NormalizedBox(0, 0, 1000, 1000);
        }
        int x0 = clamp((int) Math.floor(anchor.getX() * 1000 / page.width));
        int y0 = clamp((int) Math.floor(anchor.getY() * 1000 / page.height));
        int x1 = clamp((int) Math.ceil(anchor.getMaxX() * 1000 / page.width));
        int y1 = clamp((int) Math.ceil(anchor.getMaxY() * 1000 / page.height));
        if (x1 <= x0) {
            x1 = Math.min(1000, x0 + 1);
        }
        if (y1 <= y0) {
            y1 = Math.min(1000, y0 + 1);
        }
        return new NormalizedBox(x0, y0, x1, y1);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(1000, value));
    }

    private static ParsedStructure.BoundingBox box(
            ParsedDocument.SourceUnit unit,
            NormalizedBox value
    ) {
        return new ParsedStructure.BoundingBox(
                unit.id(),
                unit.order(),
                unit.kind(),
                value.x0,
                value.y0,
                value.x1,
                value.y1
        );
    }

    private static String unitMetadata(UnitDraft unit) {
        ObjectNode value = JSON.createObjectNode();
        value.put("sourceLabel", unit.label);
        value.put("stableAddress", unit.address);
        return json(value);
    }

    private static int countElements(Node node, String localName) {
        int count = node.getNodeType() == Node.ELEMENT_NODE
                && localName.equals(node.getLocalName()) ? 1 : 0;
        for (Node child = node.getFirstChild(); child != null;
             child = child.getNextSibling()) {
            count += countElements(child, localName);
        }
        return count;
    }

    private static void requireProvider(
            DocumentFormat format,
            ParserProviderKind provider
    ) {
        ParserProviderKind expected = format == DocumentFormat.DOCX
                ? ParserProviderKind.DOCX_POI
                : format == DocumentFormat.PPTX
                ? ParserProviderKind.PPTX_POI
                : null;
        if (expected == null || provider != expected) {
            throw new IllegalArgumentException(provider + " cannot parse " + format);
        }
    }

    private static void requireLimit(
            long value,
            long maximum,
            String code,
            String message
    ) throws ParserProcessingException {
        if (value > maximum) {
            throw new ParserProcessingException(code, message);
        }
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize Office parser metadata", exception);
        }
    }

    private static final class Draft {
        private final ParserInput input;
        private final ParserProviderKind provider;
        private final OoxmlPackageInspector.Inspection inspection;
        private final List<UnitDraft> units = new ArrayList<>();
        private final List<BlockDraft> blocks = new ArrayList<>();
        private final List<ImageDraft> images = new ArrayList<>();
        private final List<TableDraft> tables = new ArrayList<>();
        private final Set<String> imageParts = new HashSet<>();
        private final Set<String> imageHashes = new HashSet<>();
        private final Set<Integer> parentBreakBeforeBlocks = new HashSet<>();
        private int footnote;
        private int footnoteParagraphs;
        private int commentsSkipped;
        private int deletedRevisionsSkipped;
        private int tableCells;
        private int shapes;
        private int imageReferences;
        private int duplicateImages;
        private long emittedImageBytes;
        private long extractedTextCharacters;
        private int hiddenSlides;
        private int skippedShapes;
        private int skippedEmpty;

        private Draft(
                ParserInput input,
                ParserProviderKind provider,
                OoxmlPackageInspector.Inspection inspection
        ) {
            this.input = input;
            this.provider = provider;
            this.inspection = inspection;
        }

        private Draft withFootnote(int value) {
            footnote = value;
            return this;
        }

        private int addUnit(
                SourceUnitKind kind,
                String address,
                String text,
                String label,
                NormalizedBox box
        ) throws ParserProcessingException {
            requireLimit(
                    units.size() + 1L,
                    MAX_SOURCE_UNITS,
                    "OFFICE_SOURCE_UNIT_LIMIT",
                    "Office document contains too many structural elements"
            );
            extractedTextCharacters += text.length();
            requireLimit(
                    extractedTextCharacters,
                    MAX_TEXT_CHARACTERS,
                    "OFFICE_TEXT_LIMIT",
                    "Office document contains too much extracted text"
            );
            units.add(new UnitDraft(kind, address, text, label, box));
            return units.size() - 1;
        }

        private int addBlock(
                int unit,
                ParsedDocument.BlockType type,
                String text,
                String markdown,
                int headingLevel
        ) {
            blocks.add(new BlockDraft(
                    unit,
                    type,
                    text,
                    markdown,
                    headingLevel
            ));
            return blocks.size() - 1;
        }

        private void addImage(
                int unit,
                Integer block,
                String name,
                String mediaType,
                String mediaPart,
                Supplier<byte[]> bytesSupplier,
                String caption,
                NormalizedBox box
        ) throws ParserProcessingException {
            if (!mediaType.startsWith("image/")) {
                skippedShapes++;
                return;
            }
            imageReferences++;
            requireLimit(
                    imageReferences,
                    MAX_IMAGE_REFERENCES,
                    "OOXML_IMAGE_REFERENCE_LIMIT",
                    "Office document contains too many image references"
            );
            if (!imageParts.add(mediaPart)) {
                duplicateImages++;
                return;
            }
            byte[] bytes = bytesSupplier.get();
            requireLimit(
                    bytes.length,
                    OoxmlPackageInspector.MAX_ENTRY_BYTES,
                    "OOXML_IMAGE_LIMIT",
                    "Office image asset exceeds the safe limit"
            );
            String contentHash = sha256(bytes);
            if (!imageHashes.add(contentHash)) {
                duplicateImages++;
                return;
            }
            emittedImageBytes += bytes.length;
            requireLimit(
                    emittedImageBytes,
                    OoxmlPackageInspector.MAX_IMAGE_BYTES,
                    "OOXML_IMAGE_LIMIT",
                    "Office image assets exceed the safe output budget"
            );
            images.add(new ImageDraft(
                    unit,
                    block,
                    name,
                    mediaType,
                    bytes,
                    normalized(caption),
                    box
            ));
        }

        private void addTable(
                int unit,
                int block,
                String caption,
                List<ParsedStructure.Cell> cells,
                String html
        ) {
            tables.add(new TableDraft(
                    unit,
                    block,
                    caption,
                    List.copyOf(cells),
                    html
            ));
        }
    }

    private record UnitDraft(
            SourceUnitKind kind,
            String address,
            String text,
            String label,
            NormalizedBox box
    ) {
    }

    private static final class BlockDraft {
        private final int unit;
        private final ParsedDocument.BlockType type;
        private final String text;
        private String markdown;
        private final int headingLevel;

        private BlockDraft(
                int unit,
                ParsedDocument.BlockType type,
                String text,
                String markdown,
                int headingLevel
        ) {
            this.unit = unit;
            this.type = type;
            this.text = text;
            this.markdown = markdown;
            this.headingLevel = headingLevel;
        }
    }

    private record ImageDraft(
            int unit,
            Integer block,
            String name,
            String mediaType,
            byte[] bytes,
            String caption,
            NormalizedBox box
    ) {
    }

    private record TableDraft(
            int unit,
            int block,
            String caption,
            List<ParsedStructure.Cell> cells,
            String html
    ) {
    }

    private record NestedDocxTable(
            XWPFTable table,
            String address,
            String label
    ) {
    }

    private record NormalizedBox(int x0, int y0, int x1, int y1) {
    }
}
