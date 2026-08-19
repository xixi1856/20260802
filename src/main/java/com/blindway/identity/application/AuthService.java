package com.blindway.identity.application;

import com.blindway.common.api.ApiException;
import com.blindway.common.security.SecurityProperties;
import com.blindway.identity.api.LoginRequest;
import com.blindway.identity.api.RegisterRequest;
import com.blindway.identity.api.TokenResponse;
import com.blindway.identity.domain.UserRole;
import com.blindway.identity.infrastructure.IdentityMapper;
import com.blindway.identity.infrastructure.RefreshTokenRow;
import com.blindway.identity.infrastructure.UserRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final IdentityMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final SecurityProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(
            IdentityMapper mapper,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            SecurityProperties properties) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    @Transactional
    public TokenResponse register(RegisterRequest request) {
        String email = request.email().strip().toLowerCase();
        if (mapper.findUserByEmail(email).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "该邮箱已注册");
        }
        Instant now = Instant.now();
        UserRow user = new UserRow(
                UUID.randomUUID(),
                email,
                passwordEncoder.encode(request.password()),
                request.displayName().strip(),
                UserRole.USER,
                "ACTIVE",
                now,
                now);
        try {
            mapper.insertUser(user);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "该邮箱已注册");
        }
        return issueTokens(user, now);
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        UserRow user = mapper.findUserByEmail(request.email().strip().toLowerCase())
                .filter(candidate -> passwordEncoder.matches(request.password(), candidate.passwordHash()))
                .filter(candidate -> "ACTIVE".equals(candidate.status()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "邮箱或密码错误"));
        return issueTokens(user, Instant.now());
    }

    @Transactional
    public TokenResponse refresh(String rawRefreshToken) {
        Instant now = Instant.now();
        RefreshTokenRow current = mapper.findRefreshToken(hash(rawRefreshToken))
                .filter(token -> token.revokedAt() == null)
                .filter(token -> token.expiresAt().isAfter(now))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "刷新令牌无效"));
        UserRow user = mapper.findUserById(current.userId())
                .filter(candidate -> "ACTIVE".equals(candidate.status()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "刷新令牌无效"));

        String nextRaw = newRefreshToken();
        RefreshTokenRow next = tokenRow(user.id(), nextRaw, now);
        mapper.insertRefreshToken(next);
        if (mapper.revokeRefreshToken(current.id(), now, next.id()) != 1) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REUSED", "刷新令牌已被使用");
        }
        return new TokenResponse(
                accessToken(user, now), properties.accessTokenTtl().toSeconds(), nextRaw);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        mapper.findRefreshToken(hash(rawRefreshToken))
                .filter(token -> token.revokedAt() == null)
                .ifPresent(token -> mapper.revokeRefreshToken(token.id(), Instant.now(), null));
    }

    private TokenResponse issueTokens(UserRow user, Instant now) {
        String rawRefreshToken = newRefreshToken();
        mapper.insertRefreshToken(tokenRow(user.id(), rawRefreshToken, now));
        return new TokenResponse(
                accessToken(user, now), properties.accessTokenTtl().toSeconds(), rawRefreshToken);
    }

    private String accessToken(UserRow user, Instant now) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("blindway-backend")
                .subject(user.id().toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .claim("role", user.role().name())
                .claim("displayName", user.displayName())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private RefreshTokenRow tokenRow(UUID userId, String rawToken, Instant now) {
        return new RefreshTokenRow(
                UUID.randomUUID(), userId, hash(rawToken), now.plus(properties.refreshTokenTtl()), null, null, now);
    }

    private String newRefreshToken() {
        byte[] bytes = new byte[48];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
