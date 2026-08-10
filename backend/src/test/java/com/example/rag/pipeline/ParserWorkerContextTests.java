package com.example.rag.pipeline;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "rag.pipeline.worker-enabled=false"
})
class ParserWorkerContextTests {

    @Test
    void nonWebWorkerContextStarts() {
    }
}
