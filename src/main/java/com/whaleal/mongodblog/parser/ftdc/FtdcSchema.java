package com.whaleal.mongodblog.parser.ftdc;

import java.util.List;

public record FtdcSchema(List<String> paths) {
    public FtdcSchema {
        paths = List.copyOf(paths);
    }

    public int indexOf(String path) {
        return paths.indexOf(path);
    }
}
