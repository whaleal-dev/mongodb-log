package com.whaleal.mongodblog.analysis.ftdc;

import com.whaleal.mongodblog.storage.ftdc.FtdcCatalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FtdcMetricGroups {
    public static final int MAX_METRICS_PER_GROUP = 200;

    private static final List<String> SPECIAL_PREFIXES = List.of(
            "serverStatus/wiredTiger/cache/forced eviction - pages",
            "serverStatus/wiredTiger/capacity/background",
            "serverStatus/wiredTiger/capacity/bytes",
            "serverStatus/wiredTiger/capacity/time",
            "serverStatus/wiredTiger/cache/pages",
            "serverStatus/wiredTiger/cache/history",
            "serverStatus/wiredTiger/cache/eviction",
            "serverStatus/wiredTiger/cache/bytes",
            "serverStatus/wiredTiger/cache/application",
            "serverStatus/wiredTiger/transaction/transaction",
            "serverStatus/wiredTiger/transaction/rollback",
            "systemMetrics/netstat/TcpExt",
            "systemMetrics/netstat/Tcp",
            "systemMetrics/netstat/IpExt",
            "systemMetrics/netstat/Ip"
    );
    private static final List<String> EXCLUDED_PATHS = List.of(
            "start", "end", "serverStatus/globalLock/totalTime",
            "serverStatus/wiredTiger/oplog/visibility timestamp/t",
            "serverStatus/wiredTiger/oplog/visibility timestamp/i"
    );

    private FtdcMetricGroups() {
    }

    public static List<Group> from(FtdcCatalog catalog) {
        Map<String, List<FtdcCatalog.Metric>> grouped = new LinkedHashMap<>();
        catalog.metrics().stream()
                .filter(metric -> !EXCLUDED_PATHS.contains(metric.path()))
                .sorted(Comparator.comparing(FtdcCatalog.Metric::path))
                .forEach(metric -> grouped.computeIfAbsent(groupName(metric.path()), ignored -> new ArrayList<>()).add(metric));
        return grouped.entrySet().stream()
                .map(entry -> new Group(stableId(entry.getKey()), entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(Group::name))
                .toList();
    }

    public static Group require(FtdcCatalog catalog, String groupId) {
        return from(catalog).stream().filter(group -> group.groupId().equals(groupId)).findFirst()
                .orElseThrow(() -> new java.util.NoSuchElementException("FTDC 指标组不存在：" + groupId));
    }

    static String groupName(String path) {
        String group = null;
        for (String prefix : SPECIAL_PREFIXES) {
            if (path.startsWith(prefix)) {
                group = prefix;
                break;
            }
        }
        if (group == null) {
            int lastSlash = path.lastIndexOf('/');
            group = lastSlash > 0 ? path.substring(0, lastSlash) : path;
        }
        return group.startsWith("serverStatus/") ? group.substring("serverStatus/".length()) : group;
    }

    private static String stableId(String name) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
            StringBuilder id = new StringBuilder(16);
            for (int i = 0; i < 8; i++) id.append("%02x".formatted(digest[i]));
            return id.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record Group(String groupId, String name, List<FtdcCatalog.Metric> metrics) {
        public Group {
            metrics = List.copyOf(metrics);
        }

        public int metricCount() {
            return metrics.size();
        }
    }
}
