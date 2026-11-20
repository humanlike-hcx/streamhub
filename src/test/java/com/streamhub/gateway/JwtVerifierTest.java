package com.streamhub.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class JwtVerifierTest {
    static final String SECRET = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    static final String OTHER = Base64.getEncoder().encodeToString("ffffffffffffffffffffffffffffffff".getBytes());

    @Test
    void acceptsOwnTokenRejectsForgedExpiredAndGarbage() {
        var jwt = new JwtVerifier(SECRET);
        assertThat(jwt.verify(jwt.issue(42, Duration.ofMinutes(5)))).isEqualTo(42L);
        assertThat(jwt.verify(new JwtVerifier(OTHER).issue(42, Duration.ofMinutes(5)))).isNull();   // 别的密钥签的
        assertThat(jwt.verify(jwt.issue(42, Duration.ofMinutes(-5)))).isNull();                    // 已过期
        assertThat(jwt.verify("not-a-jwt")).isNull();
        assertThat(jwt.verify(null)).isNull();
    }
}
