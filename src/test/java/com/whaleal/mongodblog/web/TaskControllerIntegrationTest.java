package com.whaleal.mongodblog.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TaskControllerIntegrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        registry.add("mongodblog.data-dir", () -> dataDir.toString());
        registry.add("mongodblog.open-browser", () -> "false");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void uploadsLogAndQueriesCompletedResults() throws Exception {
        byte[] content = Files.readAllBytes(Path.of("src/test/resources/fixtures/structured.log"));
        MockMultipartFile file = new MockMultipartFile("files", "mongodb.log", "text/plain", content);

        MvcResult createResult = mockMvc.perform(multipart("/api/tasks").file(file).param("name", "线上慢日志"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn();
        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString()).path("id").asText();

        JsonNode task = waitForTerminalTask(taskId);
        assertThat(task.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(task.path("logStartEpochMillis").asLong()).isEqualTo(1717236930123L);
        assertThat(task.path("logEndEpochMillis").asLong()).isEqualTo(1717236930123L);

        mockMvc.perform(get("/api/tasks/{id}/summary", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slowQueryCount").value(1))
                .andExpect(jsonPath("$.durationDistribution[2].key").value("500ms_1s"))
                .andExpect(jsonPath("$.durationDistribution[2].count").value(1));

        mockMvc.perform(get("/api/tasks/{id}/diagnostics", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.totalParsedLines").value(1))
                .andExpect(jsonPath("$.dataQuality.structuredLines").value(1))
                .andExpect(jsonPath("$.slowQueries.total").value(1));

        MvcResult report = mockMvc.perform(get("/api/tasks/{id}/report.md", taskId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andReturn();
        String markdown = report.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(markdown)
                .contains(
                        "# MongoDB 日志分析报告",
                        "## 慢查询分析",
                        "### 解析与总体统计",
                        "### 客户端统计 · Top 20",
                        "客户端 1（IP 已脱敏）",
                        "### 操作类型统计",
                        "### 集合统计 · Top 20",
                        "### Namespace 响应量",
                        "### 执行计划分布",
                        "### CPU 耗时",
                        "find\\|sales.orders",
                        "### CPU 耗时比例分布",
                        "### 每小时平均连接数",
                        "### 查询模式 Top 50",
                        "### 最慢查询明细 · Top 5000",
                        "## 运行诊断",
                        "### 运行诊断概览",
                        "### 数据可信度",
                        "### 异常事件时间线",
                        "### Query Framework 分布",
                        "{\"amount\"")
                .doesNotContain("10.0.0.8", "OPEN", "41712");

        mockMvc.perform(get("/api/tasks/{id}/slow-queries", taskId).param("page", "1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.content[0].durationMillis").value(742))
                .andExpect(jsonPath("$.content[0].rawLine").isString());

        MvcResult pointsResult = mockMvc.perform(get("/api/tasks/{id}/slow-query-points", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.series[0].name").value("sales.orders"))
                .andExpect(jsonPath("$.series[0].data[0][1]").value(742))
                .andExpect(jsonPath("$.series[0].data[0][2]").isNotEmpty())
                .andReturn();
        assertThat(pointsResult.getResponse().getContentAsString())
                .doesNotContain("rawLine", "attributes", "queryPattern");

        String queryId = objectMapper.readTree(pointsResult.getResponse().getContentAsString())
                .path("series").path(0).path("data").path(0).path(2).asText();
        mockMvc.perform(get("/api/tasks/{id}/slow-queries/{queryId}", taskId, queryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryId").value(queryId))
                .andExpect(jsonPath("$.id").value(51803))
                .andExpect(jsonPath("$.timestampEpochMillis").value(1717236930123L))
                .andExpect(jsonPath("$.severity").value("I"))
                .andExpect(jsonPath("$.component").value("COMMAND"))
                .andExpect(jsonPath("$.context").value("conn12"))
                .andExpect(jsonPath("$.message").value("Slow query"))
                .andExpect(jsonPath("$.attributes.ns").value("sales.orders"))
                .andExpect(jsonPath("$.rawLine").isString());
    }

    @Test
    void rejectsEmptyFilesAndReportsUnknownTasks() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("files", "empty.log", "text/plain", new byte[0]);

        mockMvc.perform(multipart("/api/tasks").file(empty))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/tasks/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void exposesLegacyLogMetadataWithoutInventingAMessageId() throws Exception {
        String line = Files.readString(Path.of("src/test/resources/fixtures/legacy.log")).lines().findFirst().orElseThrow();
        MockMultipartFile file = new MockMultipartFile("files", "legacy.log", "text/plain", line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MvcResult create = mockMvc.perform(multipart("/api/tasks").file(file)).andExpect(status().isAccepted()).andReturn();
        String taskId = objectMapper.readTree(create.getResponse().getContentAsString()).path("id").asText();
        assertThat(waitForTerminalTask(taskId).path("status").asText()).isEqualTo("COMPLETED");
        mockMvc.perform(get("/api/tasks/{id}/slow-queries/0-1", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.severity").value("I"))
                .andExpect(jsonPath("$.component").value("COMMAND"))
                .andExpect(jsonPath("$.context").value("conn49"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("command local.oplog.rs")))
                .andExpect(jsonPath("$.attributes.find").value("oplog.rs"))
                .andExpect(jsonPath("$.rawLine").value(line));
    }

    @Test
    void validatesSlowQueryPagination() throws Exception {
        mockMvc.perform(get("/api/tasks/missing/slow-queries").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/tasks/missing/slow-queries").param("size", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private JsonNode waitForTerminalTask(String taskId) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            MvcResult result = mockMvc.perform(get("/api/tasks/{id}", taskId))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode task = objectMapper.readTree(result.getResponse().getContentAsString());
            if (task.path("status").asText().matches("COMPLETED|FAILED")) {
                return task;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("任务未在两秒内完成");
    }
}
