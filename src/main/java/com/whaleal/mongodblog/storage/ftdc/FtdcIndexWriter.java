package com.whaleal.mongodblog.storage.ftdc;

import com.whaleal.mongodblog.parser.ftdc.FtdcSchema;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public final class FtdcIndexWriter {
    static final byte[] MAGIC = "MLOGFTDC".getBytes(StandardCharsets.US_ASCII);
    static final int VERSION = 1;

    public void write(Path target, List<SourceFile> files, List<FtdcSchema> schemas,
                      Iterable<FtdcBlockIndex> blocks) throws IOException {
        try (BlockSpool spool = openSpool(target.resolveSibling(target.getFileName() + ".spool"))) {
            for (FtdcBlockIndex block : blocks) spool.add(block);
            spool.finish(target, files, schemas);
        }
    }

    public BlockSpool openSpool(Path spoolPath) throws IOException {
        return new BlockSpool(spoolPath);
    }

    private void assemble(Path target, Path spoolPath, int blockCount,
                          List<SourceFile> files, List<FtdcSchema> schemas) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.createDirectories(target.getParent());
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
            output.write(MAGIC);
            writeInt(output, VERSION);
            writeInt(output, files.size());
            writeInt(output, schemas.size());
            writeInt(output, blockCount);
            for (SourceFile file : files) {
                writeInt(output, file.order());
                writeString(output, file.storedName());
                writeLong(output, file.sizeBytes());
                writeString(output, file.sha256());
                writeInt(output, file.blockCount());
            }
            for (int schemaId = 0; schemaId < schemas.size(); schemaId++) {
                writeInt(output, schemaId);
                writeInt(output, schemas.get(schemaId).paths().size());
                for (String path : schemas.get(schemaId).paths()) {
                    writeString(output, path);
                }
            }
            try (InputStream input = Files.newInputStream(spoolPath)) {
                input.transferTo(output);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void writeBlock(DataOutputStream output, FtdcBlockIndex block) throws IOException {
        writeInt(output, block.fileId());
        writeInt(output, block.blockOrdinal());
        writeLong(output, block.fileOffset());
        writeInt(output, block.documentLength());
        writeInt(output, block.declaredLength());
        writeInt(output, block.compressedLength());
        writeInt(output, block.schemaId());
        writeInt(output, block.numAttributes());
        writeInt(output, block.numDeltas());
        writeLong(output, block.startEpochMillis());
        writeLong(output, block.endEpochMillis());
        writeLong(output, block.pointOffset());
        for (long value : block.baseline()) writeLong(output, value);
        for (int offset : block.deltaOffsets()) writeInt(output, offset);
        for (int zeros : block.zerosAtStart()) writeInt(output, zeros);
    }

    private void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeInt(output, bytes.length);
        output.write(bytes);
    }

    private void writeInt(DataOutputStream output, int value) throws IOException {
        output.writeInt(Integer.reverseBytes(value));
    }

    private void writeLong(DataOutputStream output, long value) throws IOException {
        output.writeLong(Long.reverseBytes(value));
    }

    public record SourceFile(int order, String storedName, long sizeBytes, String sha256, int blockCount) {
        public static SourceFile from(int order, String storedName, Path path, int blockCount) throws IOException {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = Files.newInputStream(path)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
                }
                return new SourceFile(order, storedName, Files.size(path),
                        HexFormat.of().formatHex(digest.digest()), blockCount);
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    public final class BlockSpool implements AutoCloseable {
        private final Path spoolPath;
        private DataOutputStream output;
        private int blockCount;
        private boolean finished;

        private BlockSpool(Path spoolPath) throws IOException {
            this.spoolPath = spoolPath;
            Files.createDirectories(spoolPath.getParent());
            this.output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(spoolPath)));
        }

        public void add(FtdcBlockIndex block) throws IOException {
            if (finished || output == null) throw new IllegalStateException("FTDC block spool 已关闭");
            writeBlock(output, block);
            blockCount++;
        }

        public int blockCount() {
            return blockCount;
        }

        public void finish(Path target, List<SourceFile> files, List<FtdcSchema> schemas) throws IOException {
            if (finished) throw new IllegalStateException("FTDC block spool 已发布");
            output.close();
            output = null;
            assemble(target, spoolPath, blockCount, files, schemas);
            Files.deleteIfExists(spoolPath);
            finished = true;
        }

        @Override
        public void close() throws IOException {
            if (output != null) output.close();
            if (!finished) Files.deleteIfExists(spoolPath);
        }
    }
}
