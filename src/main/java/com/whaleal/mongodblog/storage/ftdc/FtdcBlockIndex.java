package com.whaleal.mongodblog.storage.ftdc;

public record FtdcBlockIndex(
        int fileId,
        int blockOrdinal,
        long fileOffset,
        int documentLength,
        int declaredLength,
        int compressedLength,
        int schemaId,
        int numDeltas,
        long startEpochMillis,
        long endEpochMillis,
        long pointOffset,
        long[] baseline,
        int[] deltaOffsets,
        int[] zerosAtStart
) {
    public FtdcBlockIndex {
        baseline = baseline.clone();
        deltaOffsets = deltaOffsets.clone();
        zerosAtStart = zerosAtStart.clone();
        if (baseline.length != deltaOffsets.length || baseline.length != zerosAtStart.length) {
            throw new IllegalArgumentException("FTDC block 属性索引长度不一致");
        }
    }

    public int numAttributes() {
        return baseline.length;
    }

    public long pointCount() {
        return (long) numDeltas + 1;
    }
}
