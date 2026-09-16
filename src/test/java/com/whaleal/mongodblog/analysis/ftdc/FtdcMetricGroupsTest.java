package com.whaleal.mongodblog.analysis.ftdc;

import com.whaleal.mongodblog.storage.ftdc.FtdcCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FtdcMetricGroupsTest {
    @Test
    void groupsByParentAndAppliesSpecialPrefixes() {
        FtdcCatalog catalog = new FtdcCatalog(List.of(
                metric("a", "serverStatus/network/bytesIn"),
                metric("b", "serverStatus/network/bytesOut"),
                metric("c", "serverStatus/wiredTiger/cache/pages queued for eviction"),
                metric("d", "serverStatus/wiredTiger/cache/pages read into cache"),
                metric("e", "systemMetrics/netstat/TcpExt/SyncookiesSent"),
                metric("f", "systemMetrics/netstat/Tcp/ActiveOpens"),
                metric("g", "start")
        ), List.of(), 1, 1, 1L, 2L);

        List<FtdcMetricGroups.Group> groups = FtdcMetricGroups.from(catalog);

        assertThat(groups).extracting(FtdcMetricGroups.Group::name)
                .containsExactly("network", "systemMetrics/netstat/Tcp", "systemMetrics/netstat/TcpExt", "wiredTiger/cache/pages");
        assertThat(groups.stream().filter(group -> group.name().equals("network")).findFirst().orElseThrow().metrics())
                .extracting(FtdcCatalog.Metric::path)
                .containsExactly("serverStatus/network/bytesIn", "serverStatus/network/bytesOut");
        assertThat(groups).allSatisfy(group -> assertThat(group.groupId()).hasSize(16));
    }

    private FtdcCatalog.Metric metric(String id, String path) {
        return new FtdcCatalog.Metric(id, path, List.of(0));
    }
}
