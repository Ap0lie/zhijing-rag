package com.example.rag.evaluation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "rag.evaluation.worker-enabled=false",
        "rag.chat.llm.enabled=false"
})
@AutoConfigureMockMvc
class AdminOverviewIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void administratorCanReadTheTaskOrientedOverview() throws Exception {
        mockMvc.perform(get("/api/v1/admin/overview").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("admin-overview-v1"))
                .andExpect(jsonPath("$.domains.length()").value(4))
                .andExpect(jsonPath("$.domains[0].title").value("管理总览"));
    }

    @Test
    void regularUserCannotReadTheOverview() throws Exception {
        mockMvc.perform(get("/api/v1/admin/overview").with(user("reader").roles("USER")))
                .andExpect(status().isForbidden());
    }
}
