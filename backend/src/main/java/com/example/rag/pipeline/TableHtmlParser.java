package com.example.rag.pipeline;

import com.example.rag.pipeline.parser.ParsedStructure.Cell;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class TableHtmlParser {

    private static final int MAX_CELLS = 10_000;
    private static final int MAX_OCCUPIED_POSITIONS = 100_000;
    private static final int MAX_CELL_TEXT_LENGTH = 16_384;

    private TableHtmlParser() {
    }

    static List<Cell> parse(UUID tableId, String html) throws ParserProcessingException {
        Callback callback = new Callback(tableId);
        try {
            new ParserDelegator().parse(new StringReader(html), callback, true);
        } catch (IOException exception) {
            throw new ParserProcessingException(
                    "MINERU_INVALID_RESULT",
                    "MinerU table HTML could not be parsed",
                    exception
            );
        }
        if (callback.failure() != null) {
            throw new ParserProcessingException(
                    "MINERU_INVALID_RESULT",
                    callback.failure()
            );
        }
        List<Cell> cells = callback.cells();
        if (cells.isEmpty()) {
            throw new ParserProcessingException(
                    "MINERU_INVALID_RESULT",
                    "MinerU table does not contain any cells"
            );
        }
        return cells;
    }

    private static final class Callback extends HTMLEditorKit.ParserCallback {

        private final UUID tableId;
        private final List<Cell> cells = new ArrayList<>();
        private final Set<Position> occupied = new HashSet<>();
        private int tableDepth;
        private int row = -1;
        private int nextColumn;
        private MutableCell current;
        private String failure;

        private Callback(UUID tableId) {
            this.tableId = tableId;
        }

        @Override
        public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
            if (failure != null) {
                return;
            }
            if (tag == HTML.Tag.TABLE) {
                tableDepth++;
                return;
            }
            if (tableDepth != 1) {
                return;
            }
            if (tag == HTML.Tag.TR) {
                row++;
                nextColumn = 0;
            } else if ((tag == HTML.Tag.TD || tag == HTML.Tag.TH) && row >= 0 && current == null) {
                while (occupied.contains(new Position(row, nextColumn))) {
                    nextColumn++;
                }
                int rowSpan = span(attributes, HTML.Attribute.ROWSPAN);
                int columnSpan = span(attributes, HTML.Attribute.COLSPAN);
                if (cells.size() >= MAX_CELLS
                        || occupied.size() + rowSpan * columnSpan
                        > MAX_OCCUPIED_POSITIONS) {
                    failure = "MinerU table exceeds the structural cell limit";
                    return;
                }
                current = new MutableCell(row, nextColumn, rowSpan, columnSpan, tag == HTML.Tag.TH);
                occupy(current);
            }
        }

        @Override
        public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
            if (tableDepth == 1 && tag == HTML.Tag.BR && current != null) {
                current.text.append('\n');
            }
        }

        @Override
        public void handleText(char[] data, int position) {
            if (tableDepth == 1 && current != null) {
                if (current.text.length() + data.length > MAX_CELL_TEXT_LENGTH) {
                    failure = "MinerU table cell text exceeds the size limit";
                    return;
                }
                current.text.append(data);
            }
        }

        @Override
        public void handleEndTag(HTML.Tag tag, int position) {
            if (tableDepth == 1 && (tag == HTML.Tag.TD || tag == HTML.Tag.TH) && current != null) {
                String text = current.text.toString().replaceAll("\\s+", " ").strip();
                String hash = sha256(text);
                cells.add(new Cell(
                        stableId(tableId, current.row, current.column, hash),
                        current.row,
                        current.column,
                        current.rowSpan,
                        current.columnSpan,
                        current.header,
                        text,
                        hash
                ));
                nextColumn = current.column + current.columnSpan;
                current = null;
            }
            if (tag == HTML.Tag.TABLE) {
                tableDepth--;
            }
        }

        private void occupy(MutableCell cell) {
            for (int rowOffset = 0; rowOffset < cell.rowSpan; rowOffset++) {
                for (int columnOffset = 0; columnOffset < cell.columnSpan; columnOffset++) {
                    occupied.add(new Position(
                            cell.row + rowOffset,
                            cell.column + columnOffset
                    ));
                }
            }
        }

        private List<Cell> cells() {
            return List.copyOf(cells);
        }

        private String failure() {
            return failure;
        }
    }

    private static int span(MutableAttributeSet attributes, HTML.Attribute attribute) {
        Object value = attributes.getAttribute(attribute);
        if (value == null) {
            return 1;
        }
        try {
            return Math.max(1, Math.min(100, Integer.parseInt(value.toString())));
        } catch (NumberFormatException exception) {
            return 1;
        }
    }

    private static UUID stableId(UUID tableId, int row, int column, String hash) {
        return UUID.nameUUIDFromBytes(
                (tableId + ":cell:" + row + ":" + column + ":" + hash)
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Position(int row, int column) {
    }

    private static final class MutableCell {
        private final int row;
        private final int column;
        private final int rowSpan;
        private final int columnSpan;
        private final boolean header;
        private final StringBuilder text = new StringBuilder();

        private MutableCell(
                int row,
                int column,
                int rowSpan,
                int columnSpan,
                boolean header
        ) {
            this.row = row;
            this.column = column;
            this.rowSpan = rowSpan;
            this.columnSpan = columnSpan;
            this.header = header;
        }
    }
}
