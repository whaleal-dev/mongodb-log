package com.whaleal.mongodblog.web;

import com.whaleal.mongodblog.analysis.ftdc.FtdcGroupSeriesResult;
import com.whaleal.mongodblog.analysis.ftdc.FtdcSeriesQuery;
import com.whaleal.mongodblog.analysis.ftdc.FtdcSeriesService;
import com.whaleal.mongodblog.task.ftdc.FtdcTask;
import com.whaleal.mongodblog.task.ftdc.FtdcTaskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/ftdc-tasks")
public class FtdcTaskController {
    private final FtdcTaskService tasks;
    private final FtdcSeriesService series;

    public FtdcTaskController(FtdcTaskService tasks, FtdcSeriesService series) {
        this.tasks = tasks;
        this.series = series;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FtdcTask> create(@RequestParam(required = false) String name,
                                            @RequestParam("files") List<MultipartFile> files) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(tasks.create(name, files));
    }

    @GetMapping
    public List<FtdcTask> list() {
        return tasks.list();
    }

    @GetMapping("/{taskId}")
    public FtdcTask get(@PathVariable String taskId) {
        return tasks.get(taskId);
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<Void> delete(@PathVariable String taskId) {
        tasks.delete(taskId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{taskId}/groups")
    public List<GroupSummary> groups(@PathVariable String taskId) {
        return series.groups(taskId).stream()
                .map(group -> new GroupSummary(group.groupId(), group.name(), group.metricCount()))
                .toList();
    }

    @GetMapping("/{taskId}/groups/{groupId}/series")
    public FtdcGroupSeriesResult groupSeries(@PathVariable String taskId, @PathVariable String groupId,
                                             @RequestParam(required = false) Long start,
                                             @RequestParam(required = false) Long end,
                                             @RequestParam(defaultValue = "1200") int maxPoints,
                                             @RequestParam(defaultValue = "raw") String view) {
        if (maxPoints < 1 || maxPoints > 1_200) {
            throw new IllegalArgumentException("分组查询 maxPoints 必须在 1 到 1200 之间");
        }
        return series.groupSeries(taskId, groupId, new FtdcSeriesQuery(start, end, maxPoints, parseView(view)));
    }

    private FtdcSeriesQuery.View parseView(String view) {
        try {
            return FtdcSeriesQuery.View.valueOf(view.toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("view 只能是 raw 或 delta");
        }
    }

    public record GroupSummary(String groupId, String name, int metricCount) {
    }
}
