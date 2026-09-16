package com.whaleal.mongodblog.web;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.whaleal.mongodblog.analysis.SlowQueryRecord;

public record SlowQueryDetail(
        @JsonUnwrapped SlowQueryRecord query,
        Integer id,
        String severity,
        String component,
        String context,
        String message
) {
}
