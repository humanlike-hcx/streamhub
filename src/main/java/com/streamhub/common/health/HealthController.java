package com.streamhub.common.health;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /health：逐个检查依赖。
 * 全部正常返回 200 + "OK"；任何一个失败返回 503 + "DOWN"。
 * 为什么用 503：负载均衡器、k8s 探针看状态码就知道这台实例暂时不该接流量。
 */
@RestController
public class HealthController {
    private final List<DependencyCheck> checks;

    // Spring 会把所有 DependencyCheck 实现自动注入成一个 List，新增依赖只要加一个类
    public HealthController(List<DependencyCheck> checks) {
        this.checks = checks;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, String> details = new LinkedHashMap<>();
        boolean allUp = true;
        for (DependencyCheck check : checks) {
            try {
                check.check();
                details.put(check.name(), "UP");
            } catch (Exception e) {
                allUp = false;
                details.put(check.name(), "DOWN: " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", allUp ? "OK" : "DOWN");
        body.put("checks", details);
        return ResponseEntity.status(allUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
