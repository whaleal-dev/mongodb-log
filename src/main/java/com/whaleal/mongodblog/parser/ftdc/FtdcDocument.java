package com.whaleal.mongodblog.parser.ftdc;

public record FtdcDocument(
        long fileOffset,
        int length,
        int type,
        byte[] data
) {
}
