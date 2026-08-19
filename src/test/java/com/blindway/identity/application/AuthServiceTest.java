package com.blindway.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.blindway.common.api.ApiException;
import com.blindway.common.security.SecurityProperties;
import com.blindway.identity.api.RegisterRequest;
import com.blindway.identity.infrastructure.IdentityMapper;
import com.blindway.identity.infrastructure.UserRow;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private IdentityMapper mapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtEncoder jwtEncoder;

    private AuthService service;

    @BeforeEach
    void setUp() {
        SecurityProperties properties =
                new SecurityProperties("01234567890123456789012345678901", Duration.ofMinutes(15), Duration.ofDays(7));
        service = new AuthService(mapper, passwordEncoder, jwtEncoder, properties);
    }

    @Test
    void registerNormalizesEmailAndHashesPassword() {
        when(mapper.findUserByEmail("user@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("password123")).thenReturn("bcrypt");
        when(jwtEncoder.encode(any())).thenReturn(jwt("access-token"));

        var response = service.register(new RegisterRequest(" User@Example.COM ", "password123", "User"));

        ArgumentCaptor<UserRow> captor = ArgumentCaptor.forClass(UserRow.class);
        verify(mapper).insertUser(captor.capture());
        assertThat(captor.getValue().email()).isEqualTo("user@example.com");
        assertThat(captor.getValue().passwordHash()).isEqualTo("bcrypt");
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.expiresInSeconds()).isEqualTo(900);
    }

    @Test
    void duplicateEmailIsRejected() {
        when(mapper.findUserByEmail("user@example.com"))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(UserRow.class)));

        assertThatThrownBy(() -> service.register(new RegisterRequest("user@example.com", "password123", "User")))
                .isInstanceOf(ApiException.class)
                .hasMessage("该邮箱已注册");
    }

    private Jwt jwt(String token) {
        Instant now = Instant.now();
        return new Jwt(token, now, now.plusSeconds(900), Map.of("alg", "HS256"), Map.of("sub", "user"));
    }
}
