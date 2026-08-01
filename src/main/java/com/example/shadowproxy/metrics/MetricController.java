package com.example.shadowproxy.metrics;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class MetricController {
    private final MetricsReader metrics;

    public MetricController(MetricsReader metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/metric")
    public ResponseEntity<MetricsSnapshot> metric() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(metrics.snapshot());
    }
}
