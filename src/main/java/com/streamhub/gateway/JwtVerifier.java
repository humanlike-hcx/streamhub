package com.streamhub.gateway;

import com.streamhub.common.StreamhubProperties;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import java.time.Duration;
import java.util.Date;
import javax.crypto.SecretKey;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * JWT 校验：HS256 签名 + 过期时间，subject 是用户 id。
 * 只在连接的第一条 auth 消息里校验一次，结果记在连接上；之后每条弹幕不再查 Redis/数据库
 * （EchoFrame 每条都查一次 Redis，这是它的入口瓶颈之一）。
 * 取舍：用户退出登录后，已建立的连接在 token 过期前仍能发言。要立刻踢人，
 * 需要一个"注销通知"推给各节点去断开对应连接（本项目没做，见 README 局限）。
 */
@Component
public class JwtVerifier {
    private final SecretKey key;

    @Autowired
    public JwtVerifier(StreamhubProperties props) {
        this(props.jwtSecret());
    }

    public JwtVerifier(String base64Secret) {
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret));   // 少于 32 字节会直接报错
    }

    /** @return 用户 id；签名不对、过期、格式错都返回 null */
    public Long verify(String token) {
        if (token == null || token.isBlank() || token.length() > 4096) {
            return null;
        }
        try {
            String sub = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getSubject();
            long userId = Long.parseLong(sub);
            return userId > 0 ? userId : null;
        } catch (JwtException | IllegalArgumentException invalid) {
            return null;
        }
    }

    /** 签发 token：真实系统里由登录服务做，这里给测试接口和单元测试用。 */
    public String issue(long userId, Duration ttl) {
        Date now = new Date();
        return Jwts.builder().subject(Long.toString(userId)).issuedAt(now)
                .expiration(new Date(now.getTime() + ttl.toMillis())).signWith(key).compact();
    }
}
