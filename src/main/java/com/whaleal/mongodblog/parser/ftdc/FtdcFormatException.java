package com.whaleal.mongodblog.parser.ftdc;

public class FtdcFormatException extends RuntimeException {
    public FtdcFormatException(String message) {
        super(message);
    }

    public FtdcFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
