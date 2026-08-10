package com.example.rag.pipeline.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Pattern;

public final class PdfPreflightInspector {

    private static final int MIN_MEANINGFUL_CHARACTERS = 40;
    private static final Pattern TABLE_SPACING = Pattern.compile("(?m)^.*\\S\\s{3,}\\S.*$");
    private static final Pattern MULTICOLUMN_SPACING = Pattern.compile("(?m)^.{20,}\\s{8,}.{20,}$");

    public Result inspect(byte[] pdfBytes, int maximumPages) throws ParseQuarantineException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            return inspect(document, maximumPages);
        } catch (InvalidPasswordException exception) {
            throw encrypted(exception);
        } catch (ParseQuarantineException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw corrupt(exception);
        }
    }

    public Result inspect(Path pdfPath, int maximumPages) throws ParseQuarantineException {
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            return inspect(document, maximumPages);
        } catch (InvalidPasswordException exception) {
            throw encrypted(exception);
        } catch (ParseQuarantineException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw corrupt(exception);
        }
    }

    private static Result inspect(PDDocument document, int maximumPages)
            throws IOException, ParseQuarantineException {
            if (document.isEncrypted()) {
                throw new ParseQuarantineException(
                        ParseQuarantineException.Reason.ENCRYPTED_PDF,
                        "Encrypted PDFs are not parsed"
                );
            }
            int pageCount = document.getNumberOfPages();
            if (pageCount < 1) {
                throw new ParseQuarantineException(
                        ParseQuarantineException.Reason.CORRUPT_PDF,
                        "PDF contains no pages"
                );
            }
            if (pageCount > maximumPages) {
                throw new ParseQuarantineException(
                        ParseQuarantineException.Reason.PAGE_LIMIT_EXCEEDED,
                        "PDF page count exceeds the " + maximumPages + "-page parsing limit"
                );
            }

            PDFTextStripper stripper = new PDFTextStripper();
            StringBuilder rawText = new StringBuilder();
            boolean imageCandidate = false;
            for (int index = 0; index < pageCount; index++) {
                stripper.setStartPage(index + 1);
                stripper.setEndPage(index + 1);
                rawText.append(stripper.getText(document)).append('\n');
                imageCandidate |= containsImage(document.getPage(index));
            }
            long meaningful = rawText.codePoints().filter(Character::isLetterOrDigit).count();
            boolean scanned = imageCandidate && meaningful < MIN_MEANINGFUL_CHARACTERS;
            boolean table = TABLE_SPACING.matcher(rawText).find();
            boolean multicolumn = !table && MULTICOLUMN_SPACING.matcher(rawText).find();
            boolean lowQuality = meaningful < MIN_MEANINGFUL_CHARACTERS;
            return new Result(
                    pageCount,
                    scanned,
                    scanned || lowQuality,
                    multicolumn,
                    table,
                    imageCandidate,
                    lowQuality
            );
    }

    private static ParseQuarantineException encrypted(InvalidPasswordException exception) {
        return new ParseQuarantineException(
                ParseQuarantineException.Reason.ENCRYPTED_PDF,
                "A password is required to open this PDF",
                exception
        );
    }

    private static ParseQuarantineException corrupt(Exception exception) {
        return new ParseQuarantineException(
                ParseQuarantineException.Reason.CORRUPT_PDF,
                "PDF structure could not be read",
                exception
        );
    }

    private static boolean containsImage(PDPage page) throws IOException {
        if (page.getResources() == null) {
            return false;
        }
        for (COSName name : page.getResources().getXObjectNames()) {
            PDXObject object = page.getResources().getXObject(name);
            if (object instanceof PDImageXObject) {
                return true;
            }
        }
        return false;
    }

    public record Result(
            int pageCount,
            boolean scannedCandidate,
            boolean ocrRequired,
            boolean multicolumnCandidate,
            boolean tableCandidate,
            boolean imageCandidate,
            boolean lowQualityCandidate
    ) {
        public boolean requiresMineru() {
            return scannedCandidate || ocrRequired || multicolumnCandidate || tableCandidate;
        }

        public String routeReason() {
            if (scannedCandidate) {
                return "SCANNED_OR_OCR";
            }
            if (tableCandidate) {
                return "COMPLEX_LAYOUT_TABLE";
            }
            if (multicolumnCandidate) {
                return "COMPLEX_LAYOUT_MULTICOLUMN";
            }
            if (lowQualityCandidate) {
                return "LOW_QUALITY_TEXT";
            }
            return "PDFBOX_SIMPLE_TEXT";
        }
    }
}
