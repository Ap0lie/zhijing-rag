package com.example.rag.pipeline;

import com.example.rag.pipeline.parser.PdfDocumentParser;
import com.example.rag.pipeline.parser.PdfPreflightInspector;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({PipelineProperties.class, MineruProperties.class})
public class PipelineConfiguration {

    @Bean
    PdfDocumentParser pdfDocumentParser() {
        return new PdfDocumentParser();
    }

    @Bean
    PdfPreflightInspector pdfPreflightInspector() {
        return new PdfPreflightInspector();
    }
}
