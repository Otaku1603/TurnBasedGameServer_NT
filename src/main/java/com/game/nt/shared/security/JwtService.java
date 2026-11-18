package com.game.nt.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;

/**
 * JWT 签发与校验。
 *
 * <p>与原项目的差异：
 * <ul>
 *   <li>密钥强制从环境变量注入，且启动时校验长度（&lt; 32 字节直接启动失败，避免弱密钥上线）</li>
 *   <li>校验与解析合并为 {@link #verifyAndGetUserId(String)}，只在一处验签，杜绝"只解析不验签"的越权</li>
 * </ul>
 */
@Component
public class JwtService {

    /** 管理员角色声明值。玩家令牌不含该声明，因此无法访问管理接口。 */
    public static final String ROLE_ADMIN = "ADMIN";

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expire-hours:24}") long expireHours) {
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        if (raw.length < 32) {
            throw new IllegalStateException(
                    "JWT 密钥过短：至少需要 32 字节（当前 " + raw.length + "）。请通过 JWT_SECRET 环境变量注入强随机串。");
        }
        this.key = Keys.hmacShaKeyFor(raw);
        this.ttl = Duration.ofHours(expireHours);
    }

    /** 签发令牌，subject 为用户 ID。 */
    public String issue(long userId, String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(Long.toString(userId))
                .claim("username", username)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttl.toMillis()))
                .signWith(key)
                .compact();
    }

    /** 验签并返回 userId；任何异常（过期/篡改/格式错）一律返回空，由调用方决定拒绝策略。 */
    public Optional<Long> verifyAndGetUserId(String token) {
        return verify(token).map(Claims::getSubject).map(Long::parseLong);
    }

    /** 签发管理员令牌：带 role=ADMIN 声明，与玩家令牌区分。 */
    public String issueAdmin(long adminId, String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(Long.toString(adminId))
                .claim("username", username)
                .claim("role", ROLE_ADMIN)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttl.toMillis()))
                .signWith(key)
                .compact();
    }

    /** 验签并要求管理员角色；玩家令牌在这里会被拒绝。 */
    public Optional<Long> verifyAdminAndGetId(String token) {
        return verify(token)
                .filter(claims -> ROLE_ADMIN.equals(claims.get("role", String.class)))
                .map(claims -> Long.parseLong(claims.getSubject()));
    }

    private Optional<Claims> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload());
        } catch (Exception ex) {
            return Optional.empty();
        }
    }
}
