package com.example.rag.pipeline;

import com.example.rag.pipeline.parser.ParsedDocument;
import com.example.rag.pipeline.parser.ParsedStructure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpMineruProviderTests {

    private static final String CHECKSUM = "a".repeat(64);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void validatesAndMapsTablesImagesAndNormalizedCoordinates() throws Exception {
        UUID revisionId = UUID.randomUUID();
        startServer(response(revisionId, true));
        Path pdf = temporaryDirectory.resolve("input.pdf");
        Files.write(pdf, "%PDF-focused-test".getBytes(StandardCharsets.UTF_8));

        MineruProvider.Result result = provider().parse(
                pdf,
                "b".repeat(64),
                revisionId,
                2
        );

        ParsedStructure structure = result.structure();
        assertThat(result.version()).isEqualTo("3.4.4");
        assertThat(structure.packageMetadata().schemaVersion())
                .isEqualTo("mineru-content-list-v1");
        assertThat(structure.packageMetadata().manifestJson())
                .contains(revisionId.toString(), "table.png", "figure.png");
        assertThat(structure.packageMetadata().inputHash()).isEqualTo("b".repeat(64));
        assertThat(structure.blocks())
                .extracting(ParsedStructure.Block::type)
                .containsExactly(
                        ParsedDocument.BlockType.HEADING,
                        ParsedDocument.BlockType.TABLE,
                        ParsedDocument.BlockType.PARAGRAPH
                );

        assertThat(structure.tables()).singleElement().satisfies(table -> {
            assertThat(table.boundingBox().pageNumber()).isEqualTo(1);
            assertThat(table.previewAssetId()).isNotNull();
            assertThat(table.cells()).hasSize(3);
            assertThat(table.cells().getFirst().header()).isTrue();
            assertThat(table.cells().getFirst().columnSpan()).isEqualTo(2);
            assertThat(table.sourceTextHash()).hasSize(64);
        });
        assertThat(structure.images())
                .extracting(ParsedStructure.Image::type)
                .containsExactly(
                        ParsedStructure.AssetType.TABLE_PREVIEW,
                        ParsedStructure.AssetType.FIGURE
                );
        assertThat(structure.images().get(1).boundingBox().pageNumber()).isEqualTo(2);
        assertThat(structure.images().get(1).contentHash()).hasSize(64);
    }

    @Test
    void rejectsAResultPackageThatReferencesAMissingImage() throws Exception {
        UUID revisionId = UUID.randomUUID();
        startServer(response(revisionId, false));

        assertThatThrownBy(() -> provider().parse(
                "%PDF-focused-test".getBytes(StandardCharsets.UTF_8),
                revisionId,
                2
        ))
                .isInstanceOfSatisfying(ParserProcessingException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("MINERU_INVALID_RESULT");
                    assertThat(exception.getMessage()).contains("figure.png");
                });
    }

    @Test
    void rejectsAResultBoundToAnotherRevisionFilename() throws Exception {
        UUID revisionId = UUID.randomUUID();
        ObjectNode root = (ObjectNode) objectMapper.readTree(response(revisionId, true));
        ObjectNode results = (ObjectNode) root.path("results");
        JsonNode result = results.remove("revision-" + revisionId + ".pdf");
        results.set("revision-" + UUID.randomUUID() + ".pdf", result);
        startServer(objectMapper.writeValueAsBytes(root));

        assertInvalid(revisionId, "requested Revision");
    }

    @Test
    void rejectsUnreferencedAndRepeatedImages() throws Exception {
        UUID revisionId = UUID.randomUUID();
        ObjectNode extra = (ObjectNode) objectMapper.readTree(response(revisionId, true));
        ObjectNode file = (ObjectNode) extra.path("results")
                .path("revision-" + revisionId + ".pdf");
        ((ObjectNode) file.path("images"))
                .put("extra.png", "data:image/png;base64,CQoLDA==");
        startServer(objectMapper.writeValueAsBytes(extra));
        assertInvalid(revisionId, "exactly match");
        stopServer();

        ObjectNode repeated = (ObjectNode) objectMapper.readTree(response(revisionId, true));
        ObjectNode repeatedFile = (ObjectNode) repeated.path("results")
                .path("revision-" + revisionId + ".pdf");
        ArrayNode content = (ArrayNode) objectMapper.readTree(
                repeatedFile.path("content_list").asText()
        );
        ((ObjectNode) content.get(2)).put("img_path", "images/table.png");
        repeatedFile.put("content_list", objectMapper.writeValueAsString(content));
        ((ObjectNode) repeatedFile.path("images")).remove("figure.png");
        startServer(objectMapper.writeValueAsBytes(repeated));
        assertInvalid(revisionId, "more than once");
    }

    @Test
    void rejectsMalformedImageDataAndNonIntegralCoordinates() throws Exception {
        UUID revisionId = UUID.randomUUID();
        ObjectNode malformed = (ObjectNode) objectMapper.readTree(response(revisionId, true));
        ObjectNode file = (ObjectNode) malformed.path("results")
                .path("revision-" + revisionId + ".pdf");
        ((ObjectNode) file.path("images"))
                .put("figure.png", ";base64,BQYHCA==");
        startServer(objectMapper.writeValueAsBytes(malformed));
        assertInvalid(revisionId, "base64");
        stopServer();

        ObjectNode fractional = (ObjectNode) objectMapper.readTree(response(revisionId, true));
        ObjectNode fractionalFile = (ObjectNode) fractional.path("results")
                .path("revision-" + revisionId + ".pdf");
        ArrayNode content = (ArrayNode) objectMapper.readTree(
                fractionalFile.path("content_list").asText()
        );
        ((ArrayNode) content.get(0).path("bbox")).set(0, objectMapper.getNodeFactory()
                .numberNode(10.5));
        fractionalFile.put("content_list", objectMapper.writeValueAsString(content));
        startServer(objectMapper.writeValueAsBytes(fractional));
        assertInvalid(revisionId, "bounding box");
    }

    private void assertInvalid(UUID revisionId, String message) {
        assertThatThrownBy(() -> provider().parse(
                "%PDF-focused-test".getBytes(StandardCharsets.UTF_8),
                revisionId,
                2
        )).isInstanceOfSatisfying(ParserProcessingException.class, exception -> {
            assertThat(exception.code()).isEqualTo("MINERU_INVALID_RESULT");
            assertThat(exception.getMessage()).contains(message);
        });
    }

    private HttpMineruProvider provider() {
        MineruProperties properties = new MineruProperties(
                true,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "3.4.4",
                "mineru-model-v1",
                CHECKSUM,
                Duration.ofSeconds(5),
                200,
                false,
                "none"
        );
        return new HttpMineruProvider(properties, objectMapper);
    }

    private void startServer(byte[] response) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file_parse", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    private byte[] response(UUID revisionId, boolean includeFigure) throws IOException {
        ArrayNode content = objectMapper.createArrayNode();
        content.addObject()
                .put("type", "text")
                .put("text", "Phase 13B 结构资产")
                .put("text_level", 1)
                .put("page_idx", 0)
                .set("bbox", box(10, 20, 900, 80));

        ObjectNode table = content.addObject();
        table.put("type", "table");
        table.put("table_body", """
                <table>
                  <tr><th colspan="2">版本</th></tr>
                  <tr><td>V1</td><td>稳定</td></tr>
                </table>
                """);
        table.putArray("table_caption").add("版本对照");
        table.put("img_path", "images/table.png");
        table.put("page_idx", 0);
        table.set("bbox", box(50, 100, 950, 500));

        ObjectNode image = content.addObject();
        image.put("type", "image");
        image.put("img_path", "images/figure.png");
        image.putArray("image_caption").add("平台架构");
        image.put("page_idx", 1);
        image.set("bbox", box(100, 150, 900, 760));

        ObjectNode fileResult = objectMapper.createObjectNode();
        fileResult.put("md_content", "# Phase 13B 结构资产\n\n版本对照\n\n平台架构");
        fileResult.put("content_list", objectMapper.writeValueAsString(content));
        ObjectNode images = fileResult.putObject("images");
        images.put("table.png", "data:image/png;base64,AQIDBA==");
        if (includeFigure) {
            images.put("figure.png", "data:image/png;base64,BQYHCA==");
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", "3.4.4");
        root.put("backend", "vlm-engine");
        root.putObject("results")
                .set("revision-" + revisionId + ".pdf", fileResult);
        return objectMapper.writeValueAsBytes(root);
    }

    private ArrayNode box(int x0, int y0, int x1, int y1) {
        ArrayNode box = objectMapper.createArrayNode();
        box.add(x0).add(y0).add(x1).add(y1);
        return box;
    }
}
