package com.whaleal.mongodblog.web;

import java.util.List;

public record SlowQueryScatterResponse(List<Series> series) {
    public record Series(String name, List<List<Object>> data) {
    }
}
