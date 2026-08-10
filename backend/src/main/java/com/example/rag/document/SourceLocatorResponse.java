package com.example.rag.document;

import java.util.UUID;

public record SourceLocatorResponse(
        String kind,
        UUID startUnit,
        UUID endUnit,
        int startOffset,
        int endOffset,
        String address,
        String sourceTextHash,
        String normalizationVersion,
        Integer startPage,
        Integer endPage,
        String sourceLabel
) {
    public static SourceLocatorResponse pdf(
            UUID startUnit,
            UUID endUnit,
            int startPage,
            int endPage,
            int startOffset,
            int endOffset,
            String address,
            String sourceTextHash,
            String normalizationVersion
    ) {
        return new SourceLocatorResponse(
                "PAGE",
                startUnit,
                endUnit,
                startOffset,
                endOffset,
                address == null || address.isBlank()
                        ? pageAddress(startPage, endPage)
                        : address,
                sourceTextHash,
                normalizationVersion,
                startPage,
                endPage,
                pageLabel(startPage, endPage)
        );
    }

    public static SourceLocatorResponse pdfCompatibility(
            int startPage,
            int endPage
    ) {
        return pdf(
                null,
                null,
                startPage,
                endPage,
                0,
                0,
                null,
                null,
                "pdf-page-compat-v1"
        );
    }

    private static String pageAddress(int startPage, int endPage) {
        return startPage == endPage
                ? "page:" + startPage
                : "page:" + startPage + "-" + endPage;
    }

    private static String pageLabel(int startPage, int endPage) {
        return startPage == endPage
                ? "第 " + startPage + " 页"
                : "第 " + startPage + "–" + endPage + " 页";
    }
}
