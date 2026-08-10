package com.example.rag.pipeline;

import com.example.rag.pipeline.parser.ParsedDocument;
import com.example.rag.pipeline.parser.ParsedStructure;
import com.example.rag.pipeline.parser.ParsedStructure.AssetType;
import com.example.rag.pipeline.parser.ParsedStructure.Block;
import com.example.rag.pipeline.parser.ParsedStructure.BoundingBox;
import com.example.rag.pipeline.parser.ParsedStructure.Image;
import com.example.rag.pipeline.parser.ParsedStructure.Table;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
final class HttpMineruProvider implements MineruProvider {

    private static final int MAX_RESPONSE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_TOTAL_IMAGE_BYTES = 48 * 1024 * 1024;
    private static final int MAX_IMAGE_COUNT = 500;
    private static final int MAX_CONTENT_ITEMS = 10_000;
    private static final int MAX_TABLE_COUNT = 200;
    private static final int MAX_TABLE_HTML_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TOTAL_TABLE_CELLS = 50_000;
    private static final String RESULT_SCHEMA = "mineru-content-list-v1";
    private static final Set<String> IMAGE_MEDIA_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp"
    );

    private final MineruProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    HttpMineruProvider(MineruProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public Result parse(
            Path pdfPath,
            String inputHash,
            UUID revisionId,
            int expectedSourceUnitCount
    )
            throws ParserProcessingException {
        String boundary = "rag-mineru-" + UUID.randomUUID();
        HttpRequest.BodyPublisher body;
        try {
            body = multipart(boundary, pdfPath, revisionId);
        } catch (IOException exception) {
            throw new ParserProcessingException(
                    "MINERU_INPUT_UNAVAILABLE",
                    "MinerU input could not be opened",
                    exception
            );
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint("/file_parse"))
                .timeout(properties.timeout())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .POST(body)
                .build();
        try {
            HttpResponse<InputStream> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            if (response.statusCode() != 200) {
                response.body().close();
                throw failure("MINERU_FAILED", "MinerU returned HTTP " + response.statusCode());
            }
            byte[] responseBody;
            try (InputStream input = response.body()) {
                responseBody = input.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (responseBody.length > MAX_RESPONSE_BYTES) {
                throw failure("MINERU_INVALID_RESULT", "MinerU response exceeds the 64 MiB limit");
            }
            return parseResponse(
                    responseBody,
                    inputHash,
                    revisionId,
                    expectedSourceUnitCount
            );
        } catch (HttpTimeoutException exception) {
            throw new ParserProcessingException(
                    "MINERU_TIMEOUT",
                    "MinerU exceeded the " + properties.timeout().toMinutes() + "-minute timeout",
                    exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ParserProcessingException(
                    "WORKER_INTERRUPTED",
                    "MinerU request was interrupted",
                    exception
            );
        } catch (IOException exception) {
            throw new ParserProcessingException(
                    "MINERU_REQUIRED",
                    "MinerU endpoint is unavailable",
                    exception
            );
        }
    }

    private Result parseResponse(
            byte[] responseBody,
            String inputHash,
            UUID revisionId,
            int expectedSourceUnitCount
    ) throws ParserProcessingException {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String version = root.path("version").asText("");
            if (!properties.version().equals(version)) {
                throw failure(
                        "MINERU_VERSION_MISMATCH",
                        "Expected MinerU " + properties.version() + " but received " + version
                );
            }
            if (!"vlm-engine".equals(root.path("backend").asText(""))) {
                throw failure("MINERU_INVALID_RESULT", "MinerU returned an unexpected backend");
            }
            String expectedFileName = "revision-" + revisionId + ".pdf";
            JsonNode result = singleResult(root.path("results"), expectedFileName);
            String markdown = result.path("md_content").asText("");
            String contentListJson = result.path("content_list").asText("");
            if (markdown.isBlank() || contentListJson.isBlank()) {
                throw failure(
                        "MINERU_INVALID_RESULT",
                        "MinerU result must contain Markdown and content_list"
                );
            }
            JsonNode contentList = objectMapper.readTree(contentListJson);
            if (!contentList.isArray() || contentList.isEmpty()
                    || contentList.size() > MAX_CONTENT_ITEMS) {
                throw failure("MINERU_INVALID_RESULT", "MinerU content_list must be a non-empty array");
            }
            Map<String, DecodedImage> returnedImages = decodeImages(result.path("images"));
            StructureBuilder structure = new StructureBuilder(
                    revisionId,
                    expectedSourceUnitCount,
                    returnedImages
            );
            for (JsonNode item : contentList) {
                structure.add(item);
            }
            if (structure.blocks.isEmpty()) {
                throw failure("MINERU_INVALID_RESULT", "MinerU returned no usable content blocks");
            }
            structure.verifyImageClosure();

            String contentListHash = sha256(contentListJson);
            String outputHash = outputHash(markdown, contentListJson, returnedImages);
            String manifest = manifest(
                    revisionId,
                    expectedSourceUnitCount,
                    inputHash,
                    outputHash,
                    contentListHash,
                    returnedImages
            );
            ParsedStructure parsedStructure = new ParsedStructure(
                    new ParsedStructure.PackageMetadata(
                            "mineru",
                            version,
                            properties.modelRevision(),
                            inputHash,
                            outputHash,
                            RESULT_SCHEMA,
                            manifest
                    ),
                    structure.blocks,
                    structure.tables,
                    List.copyOf(structure.images.values())
            );
            return new Result(markdown, version, parsedStructure);
        } catch (ParserProcessingException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw new ParserProcessingException(
                    "MINERU_INVALID_RESULT",
                    "MinerU returned a malformed structured result",
                    exception
            );
        }
    }

    private JsonNode singleResult(JsonNode results, String expectedFileName)
            throws ParserProcessingException {
        if (!results.isObject()) {
            throw failure("MINERU_INVALID_RESULT", "MinerU returned no file result");
        }
        Iterator<Map.Entry<String, JsonNode>> entries = results.fields();
        if (!entries.hasNext()) {
            throw failure("MINERU_INVALID_RESULT", "MinerU returned no file result");
        }
        Map.Entry<String, JsonNode> entry = entries.next();
        JsonNode result = entry.getValue();
        if (entries.hasNext()) {
            throw failure("MINERU_INVALID_RESULT", "MinerU returned multiple file results");
        }
        if (!expectedFileName.equals(basename(entry.getKey()))) {
            throw failure(
                    "MINERU_INVALID_RESULT",
                    "MinerU result does not match the requested Revision"
            );
        }
        return result;
    }

    private Map<String, DecodedImage> decodeImages(JsonNode images)
            throws ParserProcessingException {
        if (!images.isObject()) {
            throw failure("MINERU_INVALID_RESULT", "MinerU images manifest is missing");
        }
        Map<String, DecodedImage> decoded = new LinkedHashMap<>();
        long totalBytes = 0;
        Iterator<Map.Entry<String, JsonNode>> entries = images.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            if (decoded.size() >= MAX_IMAGE_COUNT || !safeName(entry.getKey())) {
                throw failure("MINERU_INVALID_RESULT", "MinerU image manifest is invalid");
            }
            String dataUrl = entry.getValue().asText("");
            int separator = dataUrl.indexOf(',');
            int mediaSeparator = dataUrl.indexOf(';');
            if (!dataUrl.startsWith("data:")
                    || mediaSeparator <= "data:".length()
                    || separator <= mediaSeparator
                    || !dataUrl.substring(mediaSeparator, separator).equals(";base64")) {
                throw failure("MINERU_INVALID_RESULT", "MinerU image is not base64 encoded");
            }
            String mediaType = dataUrl.substring("data:".length(), mediaSeparator);
            if (!IMAGE_MEDIA_TYPES.contains(mediaType)) {
                throw failure("MINERU_INVALID_RESULT", "MinerU returned an unsupported image type");
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(dataUrl.substring(separator + 1));
            } catch (IllegalArgumentException exception) {
                throw new ParserProcessingException(
                        "MINERU_INVALID_RESULT",
                        "MinerU image base64 is invalid",
                        exception
                );
            }
            totalBytes += bytes.length;
            if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES
                    || totalBytes > MAX_TOTAL_IMAGE_BYTES) {
                throw failure("MINERU_INVALID_RESULT", "MinerU image size exceeds the configured limit");
            }
            decoded.put(entry.getKey(), new DecodedImage(mediaType, bytes, sha256(bytes)));
        }
        return Map.copyOf(decoded);
    }

    private String manifest(
            UUID revisionId,
            int pageCount,
            String inputHash,
            String outputHash,
            String contentListHash,
            Map<String, DecodedImage> images
    ) throws IOException {
        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("schema", RESULT_SCHEMA);
        manifest.put("parser", "mineru");
        manifest.put("parserVersion", properties.version());
        manifest.put("parserRevision", properties.modelRevision());
        manifest.put("modelManifestChecksum", properties.modelManifestChecksum());
        manifest.put("parserIdentitySource", properties.localEndpoint()
                ? "operator-attested-local-image"
                : "operator-attested-remote-endpoint");
        manifest.put("revisionId", revisionId.toString());
        manifest.put("pageCount", pageCount);
        manifest.put("inputHash", inputHash);
        manifest.put("outputHash", outputHash);
        manifest.put("contentListHash", contentListHash);
        ArrayNode files = manifest.putArray("files");
        images.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    ObjectNode file = files.addObject();
                    file.put("name", entry.getKey());
                    file.put("mediaType", entry.getValue().mediaType());
                    file.put("byteSize", entry.getValue().bytes().length);
                    file.put("hash", entry.getValue().hash());
                });
        return objectMapper.writeValueAsString(manifest);
    }

    private URI endpoint(String path) {
        String value = properties.endpoint().toString().replaceAll("/+$", "");
        return URI.create(value + path);
    }

    private static HttpRequest.BodyPublisher multipart(
            String boundary,
            Path pdfPath,
            UUID revisionId
    ) throws IOException {
        ByteArrayOutputStream prefix = new ByteArrayOutputStream(2_048);
        field(prefix, boundary, "backend", "vlm-engine");
        field(prefix, boundary, "parse_method", "ocr");
        field(prefix, boundary, "return_md", "true");
        field(prefix, boundary, "return_content_list", "true");
        field(prefix, boundary, "return_images", "true");
        field(prefix, boundary, "response_format_zip", "false");
        field(prefix, boundary, "formula_enable", "true");
        field(prefix, boundary, "table_enable", "true");
        field(prefix, boundary, "image_analysis", "true");
        write(prefix, "--" + boundary + "\r\n");
        write(prefix, "Content-Disposition: form-data; name=\"files\"; filename=\"revision-"
                + revisionId + ".pdf\"\r\n");
        write(prefix, "Content-Type: application/pdf\r\n\r\n");
        byte[] suffix = ("\r\n--" + boundary + "--\r\n")
                .getBytes(StandardCharsets.UTF_8);
        return HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofByteArray(prefix.toByteArray()),
                HttpRequest.BodyPublishers.ofFile(pdfPath),
                HttpRequest.BodyPublishers.ofByteArray(suffix)
        );
    }

    private static void field(
            ByteArrayOutputStream output,
            String boundary,
            String name,
            String value
    ) throws IOException {
        write(output, "--" + boundary + "\r\n");
        write(output, "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        write(output, value + "\r\n");
    }

    private static void write(ByteArrayOutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean safeName(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{1,255}");
    }

    private static String basename(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("").strip();
    }

    private static String textList(JsonNode node, String field) {
        JsonNode values = node.path(field);
        if (!values.isArray()) {
            return "";
        }
        List<String> text = new ArrayList<>();
        values.forEach(value -> {
            String item = value.asText("").strip();
            if (!item.isEmpty()) {
                text.add(item);
            }
        });
        return String.join("\n", text);
    }

    private static String joined(String... values) {
        List<String> present = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                present.add(value.strip());
            }
        }
        return String.join("\n", present);
    }

    private static String tableText(String caption, List<ParsedStructure.Cell> cells) {
        Map<Integer, List<ParsedStructure.Cell>> rows = new LinkedHashMap<>();
        cells.stream()
                .sorted(Comparator
                        .comparingInt(ParsedStructure.Cell::rowIndex)
                        .thenComparingInt(ParsedStructure.Cell::columnIndex))
                .forEach(cell -> rows.computeIfAbsent(cell.rowIndex(), ignored -> new ArrayList<>())
                        .add(cell));
        List<String> lines = new ArrayList<>();
        if (!caption.isBlank()) {
            lines.add(caption);
        }
        rows.values().stream()
                .map(row -> row.stream().map(ParsedStructure.Cell::text)
                        .filter(value -> !value.isBlank())
                        .reduce((left, right) -> left + " | " + right)
                        .orElse(""))
                .filter(value -> !value.isBlank())
                .forEach(lines::add);
        return String.join("\n", lines).strip();
    }

    private static String outputHash(
            String markdown,
            String contentList,
            Map<String, DecodedImage> images
    ) {
        MessageDigest digest = digest();
        update(digest, markdown);
        update(digest, contentList);
        images.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    update(digest, entry.getKey());
                    update(digest, entry.getValue().hash());
                });
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        return HexFormat.of().formatHex(digest().digest(value));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static UUID stableId(String type, UUID revisionId, Object... values) {
        StringBuilder key = new StringBuilder(type).append(':').append(revisionId);
        for (Object value : values) {
            key.append(':').append(value);
        }
        return UUID.nameUUIDFromBytes(key.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static ParserProcessingException failure(String code, String message) {
        return new ParserProcessingException(code, message);
    }

    private record DecodedImage(String mediaType, byte[] bytes, String hash) {
    }

    private final class StructureBuilder {

        private final UUID revisionId;
        private final int pageCount;
        private final Map<String, DecodedImage> returnedImages;
        private final List<Block> blocks = new ArrayList<>();
        private final List<Table> tables = new ArrayList<>();
        private final Map<String, Image> images = new LinkedHashMap<>();
        private int totalTableCells;

        private StructureBuilder(
                UUID revisionId,
                int pageCount,
                Map<String, DecodedImage> returnedImages
        ) {
            this.revisionId = revisionId;
            this.pageCount = pageCount;
            this.returnedImages = returnedImages;
        }

        private void add(JsonNode item) throws ParserProcessingException {
            String type = item.path("type").asText("");
            BoundingBox box = boundingBox(item, pageCount);
            switch (type) {
                case "text", "ref_text", "phonetic", "header", "footer",
                     "page_number", "aside_text", "page_footnote" ->
                        addText(item, box);
                case "list" -> addBlock(
                        ParsedDocument.BlockType.LIST,
                        textList(item, "list_items"),
                        0,
                        box
                );
                case "equation" -> addBlock(
                        ParsedDocument.BlockType.PARAGRAPH,
                        text(item, "text"),
                        0,
                        box
                );
                case "code" -> addBlock(
                        ParsedDocument.BlockType.PARAGRAPH,
                        joined(textList(item, "code_caption"), text(item, "code_body")),
                        0,
                        box
                );
                case "image", "chart" -> addImage(item, box, type);
                case "table" -> addTable(item, box);
                default -> throw failure(
                        "MINERU_INVALID_RESULT",
                        "MinerU returned unsupported content type " + type
                );
            }
        }

        private void addText(JsonNode item, BoundingBox box) throws ParserProcessingException {
            int headingLevel = Math.max(0, Math.min(6, item.path("text_level").asInt(0)));
            addBlock(
                    headingLevel > 0
                            ? ParsedDocument.BlockType.HEADING
                            : ParsedDocument.BlockType.PARAGRAPH,
                    text(item, "text"),
                    headingLevel,
                    box
            );
        }

        private Integer addBlock(
                ParsedDocument.BlockType type,
                String text,
                int headingLevel,
                BoundingBox box
        ) throws ParserProcessingException {
            if (text.isBlank()) {
                if (type == ParsedDocument.BlockType.TABLE) {
                    throw failure("MINERU_INVALID_RESULT", "MinerU returned an empty table");
                }
                return null;
            }
            int order = blocks.size();
            blocks.add(new Block(order, type, text, headingLevel, box));
            return order;
        }

        private void addImage(JsonNode item, BoundingBox box, String type)
                throws ParserProcessingException {
            String caption = joined(
                    text(item, "content"),
                    textList(item, type + "_caption"),
                    textList(item, type + "_footnote")
            );
            Integer blockOrder = addBlock(
                    ParsedDocument.BlockType.PARAGRAPH,
                    caption,
                    0,
                    box
            );
            requireImage(
                    text(item, "img_path"),
                    blockOrder,
                    AssetType.FIGURE,
                    box,
                    caption
            );
        }

        private void addTable(JsonNode item, BoundingBox box)
                throws ParserProcessingException {
            String html = text(item, "table_body");
            if (html.isBlank()) {
                throw failure("MINERU_INVALID_RESULT", "MinerU table HTML is missing");
            }
            if (html.getBytes(StandardCharsets.UTF_8).length > MAX_TABLE_HTML_BYTES
                    || tables.size() >= MAX_TABLE_COUNT) {
                throw failure("MINERU_INVALID_RESULT", "MinerU table output exceeds size limits");
            }
            String caption = joined(
                    textList(item, "table_caption"),
                    textList(item, "table_footnote")
            );
            UUID tableId = stableId(
                    "table",
                    revisionId,
                    tables.size(),
                    box.pageNumber(),
                    sha256(html)
            );
            List<ParsedStructure.Cell> cells = TableHtmlParser.parse(tableId, html);
            totalTableCells += cells.size();
            if (totalTableCells > MAX_TOTAL_TABLE_CELLS) {
                throw failure("MINERU_INVALID_RESULT", "MinerU returned too many table cells");
            }
            String blockText = tableText(caption, cells);
            Integer blockOrder = addBlock(
                    ParsedDocument.BlockType.TABLE,
                    blockText,
                    0,
                    box
            );
            if (blockOrder == null) {
                throw failure("MINERU_INVALID_RESULT", "MinerU table text is empty");
            }
            UUID previewId = null;
            String path = text(item, "img_path");
            if (!path.isBlank()) {
                previewId = requireImage(
                        path,
                        blockOrder,
                        AssetType.TABLE_PREVIEW,
                        box,
                        caption
                );
            }
            tables.add(new Table(
                    tableId,
                    tables.size(),
                    blockOrder,
                    previewId,
                    box,
                    caption,
                    html,
                    sha256(blockText),
                    cells
            ));
        }

        private UUID requireImage(
                String path,
                Integer blockOrder,
                AssetType type,
                BoundingBox box,
                String caption
        ) throws ParserProcessingException {
            String name = basename(path);
            if (!safeName(name)) {
                throw failure("MINERU_INVALID_RESULT", "MinerU image path is invalid");
            }
            DecodedImage decoded = returnedImages.get(name);
            if (decoded == null) {
                throw failure(
                        "MINERU_INVALID_RESULT",
                        "MinerU result package is missing image " + name
                );
            }
            Image existing = images.get(name);
            if (existing != null) {
                throw failure(
                        "MINERU_INVALID_RESULT",
                        "MinerU image path is referenced more than once: " + name
                );
            }
            UUID id = stableId("image", revisionId, name, decoded.hash());
            images.put(name, new Image(
                    id,
                    images.size(),
                    blockOrder,
                    type,
                    box,
                    name,
                    decoded.mediaType(),
                    decoded.bytes(),
                    decoded.hash(),
                    caption
            ));
            return id;
        }

        private void verifyImageClosure() throws ParserProcessingException {
            if (!returnedImages.keySet().equals(images.keySet())) {
                throw failure(
                        "MINERU_INVALID_RESULT",
                        "MinerU image manifest must exactly match structured image references"
                );
            }
        }
    }

    private static BoundingBox boundingBox(JsonNode item, int pageCount)
            throws ParserProcessingException {
        int pageIndex = strictInt(item.path("page_idx"));
        JsonNode bbox = item.path("bbox");
        if (pageIndex < 0 || pageIndex >= pageCount || !bbox.isArray() || bbox.size() != 4) {
            throw failure("MINERU_INVALID_RESULT", "MinerU page or bounding box is invalid");
        }
        try {
            return new BoundingBox(
                    pageIndex + 1,
                    strictInt(bbox.get(0)),
                    strictInt(bbox.get(1)),
                    strictInt(bbox.get(2)),
                    strictInt(bbox.get(3))
            );
        } catch (IllegalArgumentException exception) {
            throw new ParserProcessingException(
                    "MINERU_INVALID_RESULT",
                    "MinerU bounding box is outside the normalized page",
                    exception
            );
        }
    }

    private static int strictInt(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToInt()
                ? value.intValue() : -1;
    }
}
