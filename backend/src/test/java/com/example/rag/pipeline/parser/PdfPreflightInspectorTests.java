package com.example.rag.pipeline.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfPreflightInspectorTests {

    private final PdfPreflightInspector inspector = new PdfPreflightInspector();

    @Test
    void identifiesSimpleElectronicPdfForPdfBox() throws Exception {
        PdfPreflightInspector.Result result = inspector.inspect(electronicPdf(), 200);

        assertThat(result.pageCount()).isOne();
        assertThat(result.requiresMineru()).isFalse();
        assertThat(result.routeReason()).isEqualTo("PDFBOX_SIMPLE_TEXT");
    }

    @Test
    void quarantinesCorruptInputBeforeChoosingAParser() {
        assertThatThrownBy(() -> inspector.inspect("%PDF-broken".getBytes(), 200))
                .isInstanceOf(ParseQuarantineException.class)
                .extracting(exception -> ((ParseQuarantineException) exception).reason())
                .isEqualTo(ParseQuarantineException.Reason.CORRUPT_PDF);
    }

    private static byte[] electronicPdf() throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText("Enterprise retrieval uses authorized evidence and traceable citations.");
                content.newLineAtOffset(0, -24);
                content.showText("This second sentence keeps the electronic PDF above the quality threshold.");
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
