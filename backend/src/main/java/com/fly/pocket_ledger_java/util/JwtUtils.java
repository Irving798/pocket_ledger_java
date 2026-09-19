package com.fly.pocket_ledger_java.util;

import com.fly.pocket_ledger_java.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类：负责登录令牌的签发与校验解析。
 * 登录成功后由 AuthServiceImpl 调用 generateToken 签发；每次请求由 AuthInterceptor 调用 parseToken 校验。
 * 注意：JWT 载荷只做编码不做加密，任何人都能解开查看，不能存放敏感信息。
 */
@Component
public class JwtUtils {

    /** HMAC-SHA256 签名密钥，取自 jwt.secret，要求至少 32 字节。 */
    private final SecretKey secretKey;

    /** 令牌有效期（毫秒），构造时由 jwt.expire-minutes 换算并缓存。 */
    private final long expireMillis;

    /**
     * 构造时把配置转换为运行时对象；密钥长度不足会抛异常，使应用启动即失败。
     *
     * @param properties jwt 配置项（密钥、有效期）
     */
    public JwtUtils(JwtProperties properties) {
        // 显式指定 UTF-8，避免平台默认字符集不同导致同一配置算出不同密钥
        this.secretKey = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
        // 分钟转毫秒；L 不可省略，否则按 int 相乘会溢出
        this.expireMillis = properties.getExpireMinutes() * 60_000L;
    }

    /**
     * 签发 token：载荷写入 userId（sub）与 username，不写敏感信息。
     *
     * @param userId   用户 ID，写入标准字段 sub（JWT 规定为字符串，解析时再转回 Long）
     * @param username 自定义字段，便于日志排查与前端展示
     * @return header.payload.signature 三段式令牌字符串
     */
    public String generateToken(Long userId, String username) {
        Date now = new Date();
        Date expiresAt = new Date(now.getTime() + expireMillis);
        return Jwts.builder()
                .setSubject(String.valueOf(userId))          // sub：用户 ID
                .claim("username", username)                 // 自定义业务字段
                .setIssuedAt(now)                            // iat：签发时间
                .setExpiration(expiresAt)                    // exp：过期时间
                .signWith(secretKey, SignatureAlgorithm.HS256) // HS256 签名，载荷被改则验签失败
                .compact();
    }

    /**
     * 校验签名、格式与过期时间，并解析出当前用户。
     * 签名错、格式错、过期都会抛 JwtException 子类异常；本方法不吞异常，
     * 由调用方区分记录日志，对客户端统一返回 401。
     *
     * @param token 已去掉 "Bearer " 前缀的原始令牌
     * @return 解析出的登录用户
     */
    public LoginUser parseToken(String token) {
        // 用同一密钥重新计算签名并比对，同时校验 exp 与格式
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(secretKey)
                .build()
                .parseClaimsJws(token)
                .getBody();

        // sub 为字符串，需转回 Long；字段缺失或非法会抛异常，按“令牌无效”处理
        return new LoginUser(
                Long.valueOf(claims.getSubject()),
                claims.get("username", String.class)
        );
    }
}
