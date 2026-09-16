package com.whaleal.mongodblog.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whaleal.mongodblog.parser.ftdc.FtdcFixtureBuilder;
import org.bson.BsonDateTime;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class FtdcTaskControllerIntegrationTest {
    @TempDir
    static Path dataDirectory;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("mongodblog.data-dir", () -> dataDirectory.toString());
        registry.add("mongodblog.open-browser", () -> "false");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void completesUploadGroupSeriesAndDeleteFlow() throws Exception {
        BsonDocument baseline = new BsonDocument("start", new BsonDateTime(1_000))
                .append("server", new BsonDocument("counter", new BsonInt64(5)));
        byte[] bytes = FtdcFixtureBuilder.file(FtdcFixtureBuilder.metadata(),
                FtdcFixtureBuilder.block(baseline, List.of(
                        new long[]{1_000, 2_000, 3_000}, new long[]{5, 8, 13}
                )));
        MockMultipartFile file = new MockMultipartFile("files", "metrics.test", null, bytes);
        MvcResult created = mockMvc.perform(multipart("/api/ftdc-tasks").file(file).param("name", "FTDC 测试"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").isNotEmpty()).andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asText();
        JsonNode task = waitForTask(id);
        assertThat(task.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(task.path("blockCount").asInt()).isEqualTo(1);

        MvcResult groupsResult = mockMvc.perform(get("/api/ftdc-tasks/{id}/groups", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("server"))
                .andExpect(jsonPath("$[0].metricCount").value(1)).andReturn();
        String groupId = objectMapper.readTree(groupsResult.getResponse().getContentAsString()).path(0).path("groupId").asText();
        mockMvc.perform(get("/api/ftdc-tasks/{id}/groups/{groupId}/series", id, groupId)
                        .param("maxPoints", "2").param("view", "raw"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("server"))
                .andExpect(jsonPath("$.series[0].path").value("server/counter"))
                .andExpect(jsonPath("$.series[0].values.length()").value(2));

        mockMvc.perform(get("/api/ftdc-tasks/{id}/catalog", id)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/ftdc-tasks/{id}/metrics/x/series", id)).andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/ftdc-tasks/{id}", id)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/ftdc-tasks/{id}", id)).andExpect(status().isNotFound());
    }

    @Test
    void rejectsEmptyTooManyAndInvalidQueryParameters() throws Exception {
        mockMvc.perform(multipart("/api/ftdc-tasks")
                        .file(new MockMultipartFile("files", "empty", null, new byte[0])))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        var request = multipart("/api/ftdc-tasks");
        for (int i = 0; i < 21; i++) request.file(new MockMultipartFile("files", "m" + i, null, new byte[]{1}));
        mockMvc.perform(request).andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/ftdc-tasks/missing/groups/x/series").param("maxPoints", "1201"))
                .andExpect(status().isBadRequest());
    }

    private JsonNode waitForTask(String id) throws Exception {
        for (int i = 0; i < 100; i++) {
            MvcResult response = mockMvc.perform(get("/api/ftdc-tasks/{id}", id)).andExpect(status().isOk()).andReturn();
            JsonNode task = objectMapper.readTree(response.getResponse().getContentAsString());
            if (task.path("status").asText().matches("COMPLETED|FAILED")) return task;
            Thread.sleep(20);
        }
        throw new AssertionError("FTDC 任务未在两秒内完成");
    }
}
