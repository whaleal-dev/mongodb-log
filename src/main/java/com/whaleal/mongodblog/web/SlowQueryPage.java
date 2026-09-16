package com.whaleal.mongodblog.web;

import com.whaleal.mongodblog.analysis.SlowQueryRecord;

import java.util.List;

public record SlowQueryPage(
        int page,
        int size,
        long total,
        List<SlowQueryRecord> content
) {
}

