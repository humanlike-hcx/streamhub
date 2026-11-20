package com.streamhub.common.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 纯单元测试：直接 new 控制器、塞假的检查项，不启动 Spring、不需要任何中间件，
 * 所以在 GitHub Actions 上也能跑。
 */
class HealthControllerTest {

    /** 造一个假检查：healthy=false 时抛异常，模拟依赖挂了。 */
    private static DependencyCheck fake(String name, boolean healthy) {
        return new DependencyCheck() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void check() {
                if (!healthy) {
                    throw new IllegalStateException("connection refused");
                }
            }
        };
    }

    @Test
    void allDependenciesUpReturns200() {
        var controller = new HealthController(List.of(fake("mysql", true), fake("redis", true)));

        ResponseEntity<Map<String, Object>> response = controller.health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "OK");
    }

    @Test
    void anyDependencyDownReturns503AndNamesIt() {
        var controller = new HealthController(List.of(fake("mysql", true), fake("redis", false)));

        ResponseEntity<Map<String, Object>> response = controller.health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("status", "DOWN");
        @SuppressWarnings("unchecked")
        var checks = (Map<String, String>) response.getBody().get("checks");
        assertThat(checks.get("mysql")).isEqualTo("UP");
        assertThat(checks.get("redis")).startsWith("DOWN");
    }
}
