package com.whaleal.mongodblog.analysis.ftdc;

import com.whaleal.mongodblog.task.ftdc.FtdcOperationGate;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

@Service
public class FtdcMetricExportService {
    private final FtdcSeriesService seriesService;
    private final FtdcOperationGate gate;

    public FtdcMetricExportService(FtdcSeriesService seriesService, FtdcOperationGate gate) {
        this.seriesService = seriesService;
        this.gate = gate;
    }

    public void export(String taskId, String metricId, OutputStream destination) throws IOException {
        try {
            gate.run(() -> write(taskId, metricId, destination));
        } catch (IOException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("FTDC 导出被中断", e);
        } catch (Exception e) {
            throw new IOException("FTDC 导出失败：" + e.getMessage(), e);
        }
    }

    private void write(String taskId, String metricId, OutputStream destination) throws Exception {
        FtdcSeriesService.MetricContext context = seriesService.context(taskId, metricId);
        try (GZIPOutputStream gzip = new GZIPOutputStream(destination);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(gzip, StandardCharsets.UTF_8))) {
            writer.write("timestampEpochMillis,value\n");
            seriesService.forEachPoint(context, null, null, (timestamp, value) -> {
                writer.write(Long.toString(timestamp));
                writer.write(',');
                writer.write(Long.toString(value));
                writer.newLine();
            });
        }
    }
}
