package com.whaleal.mongodblog.web;

import com.whaleal.mongodblog.task.ApplicationMaintenanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SystemControllerTest {
    private ApplicationMaintenanceService maintenance;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        maintenance = mock(ApplicationMaintenanceService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SystemController(maintenance))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsCurrentMemoryUsage() throws Exception {
        when(maintenance.memoryUsage()).thenReturn(
                new ApplicationMaintenanceService.MemoryUsage(512, 2048, 25));

        mockMvc.perform(get("/api/system/memory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedBytes").value(512))
                .andExpect(jsonPath("$.maxBytes").value(2048))
                .andExpect(jsonPath("$.usagePercent").value(25));
    }

    @Test
    void clearsAllApplicationData() throws Exception {
        mockMvc.perform(delete("/api/system/data"))
                .andExpect(status().isNoContent());

        verify(maintenance).clearAllData();
    }
}
