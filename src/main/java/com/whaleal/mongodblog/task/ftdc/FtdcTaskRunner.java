package com.whaleal.mongodblog.task.ftdc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whaleal.mongodblog.parser.ftdc.FtdcBlockScanner;
import com.whaleal.mongodblog.parser.ftdc.FtdcDocument;
import com.whaleal.mongodblog.parser.ftdc.FtdcFileReader;
import com.whaleal.mongodblog.parser.ftdc.FtdcSchema;
import com.whaleal.mongodblog.storage.ftdc.FtdcBlockIndex;
import com.whaleal.mongodblog.storage.ftdc.FtdcCatalog;
import com.whaleal.mongodblog.storage.ftdc.FtdcIndexWriter;
import com.whaleal.mongodblog.storage.ftdc.FtdcTaskRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

@Component
public class FtdcTaskRunner {
    private final FtdcTaskRepository repository;
    private final FtdcOperationGate gate;
    private final ObjectMapper objectMapper;
    private final FtdcFileReader fileReader = new FtdcFileReader();
    private final FtdcBlockScanner blockScanner = new FtdcBlockScanner();
    private final FtdcIndexWriter indexWriter = new FtdcIndexWriter();

    public FtdcTaskRunner(FtdcTaskRepository repository, FtdcOperationGate gate, ObjectMapper objectMapper) {
        this.repository = repository;
        this.gate = gate;
        this.objectMapper = objectMapper;
    }

    public void run(String taskId) {
        FtdcTask task = repository.findTask(taskId)
                .orElseThrow(() -> new NoSuchElementException("FTDC 任务不存在：" + taskId));
        FtdcTask running = task.running(System.currentTimeMillis());
        repository.saveTask(running);
        try {
            FtdcTask completed = gate.call(() -> buildIndex(running));
            repository.saveTask(completed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cleanup(repository.workDirectory(taskId));
            repository.saveTask(running.failed(System.currentTimeMillis(), "FTDC 分析被中断"));
        } catch (Exception e) {
            cleanup(repository.workDirectory(taskId));
            repository.saveTask(running.failed(System.currentTimeMillis(), "FTDC 分析失败：" + safeMessage(e)));
        }
    }

    private FtdcTask buildIndex(FtdcTask running) throws Exception {
        Path work = repository.workDirectory(running.id());
        Path source = work.resolve("source");
        List<FtdcSchema> schemas = new ArrayList<>();
        List<FtdcIndexWriter.SourceFile> sourceFiles = new ArrayList<>();
        long[] pointOffset = {0};
        long[] range = {Long.MAX_VALUE, Long.MIN_VALUE};
        long processedBytes = 0;

        try (FtdcIndexWriter.BlockSpool spool = indexWriter.openSpool(work.resolve("blocks.spool"))) {
            for (int fileId = 0; fileId < running.files().size(); fileId++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                FtdcTaskInputFile input = running.files().get(fileId);
                Path path = source.resolve(input.storedName()).normalize();
                if (!path.startsWith(source)) throw new IOException("FTDC 源文件路径越界");
                int currentFileId = fileId;
                int[] ordinal = {0};
                FtdcFileReader.ScanSummary summary = fileReader.scan(path, document -> {
                    if (document.type() != 1) return;
                    if (Thread.currentThread().isInterrupted()) throw new IOException("FTDC 分析被中断");
                    FtdcBlockScanner.ScannedBlock scanned = blockScanner.scan(
                            document.data(), document.fileOffset(), ordinal[0]);
                    int schemaId = schemas.indexOf(scanned.schema());
                    if (schemaId < 0) {
                        schemas.add(scanned.schema());
                        schemaId = schemas.size() - 1;
                    }
                    spool.add(toIndex(currentFileId, ordinal[0], document, scanned, schemaId, pointOffset[0]));
                    pointOffset[0] += (long) scanned.numDeltas() + 1;
                    range[0] = Math.min(range[0], scanned.startEpochMillis());
                    range[1] = Math.max(range[1], scanned.endEpochMillis());
                    ordinal[0]++;
                });
                sourceFiles.add(FtdcIndexWriter.SourceFile.from(fileId, input.storedName(), path, summary.metricsBlockCount()));
                processedBytes += input.sizeBytes();
                repository.saveTask(running.progress(processedBytes, spool.blockCount()));
            }

            FtdcCatalog catalog = FtdcCatalog.create(schemas, spool.blockCount(), pointOffset[0],
                    range[0] == Long.MAX_VALUE ? null : range[0], range[1] == Long.MIN_VALUE ? null : range[1]);
            spool.finish(work.resolve("blocks.idx"), sourceFiles, schemas);
            writeCatalog(work.resolve("catalog.json"), catalog);
            publish(work, repository.taskDirectory(running.id()));
            return running.completed(System.currentTimeMillis(), catalog.blockCount(), catalog.metrics().size(),
                    pointOffset[0], catalog.startEpochMillis(), catalog.endEpochMillis());
        }
    }

    private FtdcBlockIndex toIndex(int fileId, int ordinal, FtdcDocument document,
                                   FtdcBlockScanner.ScannedBlock scanned, int schemaId, long pointOffset) {
        return new FtdcBlockIndex(fileId, ordinal, document.fileOffset(), document.length(),
                scanned.declaredLength(), scanned.compressedLength(), schemaId, scanned.numDeltas(),
                scanned.startEpochMillis(), scanned.endEpochMillis(), pointOffset, scanned.baseline(),
                scanned.deltaOffsets(), scanned.zerosAtStart());
    }

    private void writeCatalog(Path target, FtdcCatalog catalog) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), catalog);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void publish(Path work, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(work, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(work, target);
        }
    }

    private void cleanup(Path directory) {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) {
            directory.toFile().deleteOnExit();
        }
    }

    private String safeMessage(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }
}
