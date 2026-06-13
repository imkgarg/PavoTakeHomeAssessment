package com.pavo.scanner.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "SCAN_API_KEY=test-secret-key")
class ContentScannerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthDoesNotRequireAuth() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"ok\"}"));
    }

    @Test
    void scanRejectsMissingAuth() throws Exception {
        mockMvc.perform(post("/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void scanRejectsInvalidAuth() throws Exception {
        mockMvc.perform(post("/scan")
                        .header("Authorization", "Bearer wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void scanAcceptsValidAuth() throws Exception {
        mockMvc.perform(post("/scan")
                        .header("Authorization", "Bearer test-secret-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello world\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.safe").value(true));
    }
}
