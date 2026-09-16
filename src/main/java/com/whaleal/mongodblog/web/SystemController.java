package com.whaleal.mongodblog.web;

import com.whaleal.mongodblog.task.ApplicationMaintenanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {
    private final ApplicationMaintenanceService maintenance;

    public SystemController(ApplicationMaintenanceService maintenance) {
        this.maintenance = maintenance;
    }

    @GetMapping("/memory")
    public ApplicationMaintenanceService.MemoryUsage memory() {
        return maintenance.memoryUsage();
    }

    @DeleteMapping("/data")
    public ResponseEntity<Void> clearAllData() {
        maintenance.clearAllData();
        return ResponseEntity.noContent().build();
    }
}
