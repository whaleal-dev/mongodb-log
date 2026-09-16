package com.whaleal.mongodblog.task;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

final class CountingInputStream extends FilterInputStream {
    private long count;

    CountingInputStream(InputStream input) {
        super(input);
    }

    long count() {
        return count;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value >= 0) {
            count++;
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int read = super.read(buffer, offset, length);
        if (read > 0) {
            count += read;
        }
        return read;
    }
}

