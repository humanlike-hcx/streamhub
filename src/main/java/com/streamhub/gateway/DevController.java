package com.streamhub.gateway;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开发/演示用接口：签发 token、创建视频。本项目不做登录和视频管理，用这两个接口代替。
 * 只有 streamhub.dev-endpoints=true 时才会注册，生产环境默认关闭（否则任何人都能给自己发 token）。
 */
@RestController
@ConditionalOnProperty(name = "streamhub.dev-endpoints", havingValue = "true")
public class DevController {
    private final JwtVerifier jwt;
    private final JdbcTemplate jdbc;

    public DevController(JwtVerifier jwt, JdbcTemplate jdbc) {
        this.jwt = jwt;
        this.jdbc = jdbc;
    }

    @PostMapping("/dev/token")
    public Map<String, String> token(@RequestParam long userId) {
        return Map.of("token", jwt.issue(userId, Duration.ofHours(24)));
    }

    @PostMapping("/dev/videos/{id}")
    public Map<String, Object> video(@PathVariable long id, @RequestParam(defaultValue = "1") int status) {
        jdbc.update("INSERT INTO video (id, status) VALUES (?, ?) AS n ON DUPLICATE KEY UPDATE status = n.status", id, status);
        return Map.of("id", id, "status", status);
    }
}
