package com.example.rag.document;

import com.example.rag.persistence.DocumentFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OoxmlPackageInspectorTests {

    @TempDir
    Path directory;

    @Test
    void rejectsExternalRelationshipsBeforePoiReadsThePackage()
            throws Exception {
        Path path = directory.resolve("external.docx");
        try (ZipOutputStream output = new ZipOutputStream(
                Files.newOutputStream(path)
        )) {
            entry(
                    output,
                    "[Content_Types].xml",
                    """
                    <Types>
                      <Override PartName="/word/document.xml"
                        ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """
            );
            entry(output, "word/document.xml", "<document/>");
            entry(
                    output,
                    "word/_rels/document.xml.rels",
                    """
                    <Relationships>
                      <Relationship Target="https://example.invalid/payload"
                        TargetMode="External"/>
                    </Relationships>
                    """
            );
        }

        assertThatThrownBy(() ->
                new OoxmlPackageInspector().inspect(path, DocumentFormat.DOCX)
        ).isInstanceOfSatisfying(
                OoxmlPackageInspector.OoxmlValidationException.class,
                exception -> assertThat(exception.code())
                        .isEqualTo("OOXML_EXTERNAL_RELATIONSHIP")
        );
    }

    @Test
    void rejectsUtf16AndEntityEncodedExternalRelationships()
            throws Exception {
        Path path = directory.resolve("encoded-external.docx");
        try (ZipOutputStream output = new ZipOutputStream(
                Files.newOutputStream(path)
        )) {
            entry(
                    output,
                    "[Content_Types].xml",
                    contentTypes().getBytes(StandardCharsets.UTF_16)
            );
            entry(output, "word/document.xml", "<document/>");
            entry(
                    output,
                    "word/_rels/document.xml.rels",
                    """
                    <?xml version="1.0" encoding="UTF-16"?>
                    <Relationships>
                      <Relationship Target="https://example.invalid/payload"
                        TargetMode="&#x45;xternal"/>
                    </Relationships>
                    """.getBytes(StandardCharsets.UTF_16)
            );
        }

        assertThatThrownBy(() ->
                new OoxmlPackageInspector().inspect(path, DocumentFormat.DOCX)
        ).isInstanceOfSatisfying(
                OoxmlPackageInspector.OoxmlValidationException.class,
                exception -> assertThat(exception.code())
                        .isEqualTo("OOXML_EXTERNAL_RELATIONSHIP")
        );
    }

    @Test
    void requiresTheExpectedContentTypeOnTheExactMainPart()
            throws Exception {
        Path path = directory.resolve("wrong-main-part.docx");
        try (ZipOutputStream output = new ZipOutputStream(
                Files.newOutputStream(path)
        )) {
            entry(
                    output,
                    "[Content_Types].xml",
                    """
                    <Types>
                      <Override PartName="/custom/decoy.xml"
                        ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                      <Override PartName="/word/document.xml"
                        ContentType="application/xml"/>
                    </Types>
                    """
            );
            entry(output, "word/document.xml", "<document/>");
        }

        assertThatThrownBy(() ->
                new OoxmlPackageInspector().inspect(path, DocumentFormat.DOCX)
        ).isInstanceOfSatisfying(
                OoxmlPackageInspector.OoxmlValidationException.class,
                exception -> assertThat(exception.code())
                        .isEqualTo("OOXML_FORMAT_MISMATCH")
        );
    }

    @Test
    void rejectsEmbeddedOleParts() throws Exception {
        Path path = directory.resolve("active.docx");
        packageWith(path, "word/embeddings/oleObject1.bin", "active".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() ->
                new OoxmlPackageInspector().inspect(path, DocumentFormat.DOCX)
        ).isInstanceOfSatisfying(
                OoxmlPackageInspector.OoxmlValidationException.class,
                exception -> assertThat(exception.code())
                        .isEqualTo("OOXML_ACTIVE_CONTENT_REJECTED")
        );
    }

    @Test
    void rejectsMacroAndEncryptedPackageParts() throws Exception {
        Path macro = directory.resolve("macro.docx");
        packageWith(macro, "word/vbaProject.bin", new byte[]{1});
        Path encrypted = directory.resolve("encrypted.docx");
        packageWith(encrypted, "EncryptedPackage", new byte[]{1});

        assertRejectedActiveContent(macro);
        assertRejectedActiveContent(encrypted);
    }

    @Test
    void rejectsCorruptAndSuspiciouslyCompressedPackages() throws Exception {
        Path corrupt = directory.resolve("corrupt.docx");
        Files.writeString(corrupt, "not an OOXML package");
        assertThatThrownBy(() ->
                new OoxmlPackageInspector().inspect(corrupt, DocumentFormat.DOCX)
        ).isInstanceOfSatisfying(
                OoxmlPackageInspector.OoxmlValidationException.class,
                exception -> assertThat(exception.code())
                        .isEqualTo("OOXML_SIGNATURE_INVALID")
        );

        Path bomb = directory.resolve("bomb.docx");
        packageWith(bomb, "word/large.xml", new byte[2 * 1024 * 1024]);
        assertThatThrownBy(() ->
                new OoxmlPackageInspector().inspect(bomb, DocumentFormat.DOCX)
        ).isInstanceOfSatisfying(
                OoxmlPackageInspector.OoxmlValidationException.class,
                exception -> assertThat(exception.code())
                        .isEqualTo("OOXML_ZIP_BOMB")
        );
    }

    private static void assertRejectedActiveContent(Path path) {
        assertThatThrownBy(() ->
                new OoxmlPackageInspector().inspect(path, DocumentFormat.DOCX)
        ).isInstanceOfSatisfying(
                OoxmlPackageInspector.OoxmlValidationException.class,
                exception -> assertThat(exception.code())
                        .isEqualTo("OOXML_ACTIVE_CONTENT_REJECTED")
        );
    }

    private static void packageWith(
            Path path,
            String extraName,
            byte[] extra
    ) throws Exception {
        try (ZipOutputStream output = new ZipOutputStream(
                Files.newOutputStream(path)
        )) {
            entry(
                    output,
                    "[Content_Types].xml",
                    contentTypes()
            );
            entry(output, "word/document.xml", "<document/>");
            entry(output, extraName, extra);
        }
    }

    private static String contentTypes() {
        return """
                <Types>
                  <Override PartName="/word/document.xml"
                    ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>
                """;
    }

    private static void entry(
            ZipOutputStream output,
            String name,
            String value
    ) throws Exception {
        output.putNextEntry(new ZipEntry(name));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private static void entry(
            ZipOutputStream output,
            String name,
            byte[] value
    ) throws Exception {
        output.putNextEntry(new ZipEntry(name));
        output.write(value);
        output.closeEntry();
    }
}
