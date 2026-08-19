package com.blindway.identity.infrastructure;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface IdentityMapper {

    @Insert(
            """
            INSERT INTO app_user
                (id, email, password_hash, display_name, role, status, created_at, updated_at)
            VALUES
                (#{id}, #{email}, #{passwordHash}, #{displayName}, #{role}, #{status}, #{createdAt}, #{updatedAt})
            """)
    void insertUser(UserRow user);

    @Select(
            """
            SELECT id, email, password_hash, display_name, role, status, created_at, updated_at
            FROM app_user
            WHERE lower(email) = lower(#{email})
            """)
    Optional<UserRow> findUserByEmail(String email);

    @Select(
            """
            SELECT id, email, password_hash, display_name, role, status, created_at, updated_at
            FROM app_user
            WHERE id = #{id}
            """)
    Optional<UserRow> findUserById(UUID id);

    @Insert(
            """
            INSERT INTO refresh_token
                (id, user_id, token_hash, expires_at, revoked_at, replaced_by, created_at)
            VALUES
                (#{id}, #{userId}, #{tokenHash}, #{expiresAt}, #{revokedAt}, #{replacedBy}, #{createdAt})
            """)
    void insertRefreshToken(RefreshTokenRow token);

    @Select(
            """
            SELECT id, user_id, token_hash, expires_at, revoked_at, replaced_by, created_at
            FROM refresh_token
            WHERE token_hash = #{tokenHash}
            """)
    Optional<RefreshTokenRow> findRefreshToken(String tokenHash);

    @Update(
            """
            UPDATE refresh_token
            SET revoked_at = #{revokedAt}, replaced_by = #{replacedBy}
            WHERE id = #{id} AND revoked_at IS NULL
            """)
    int revokeRefreshToken(
            @Param("id") UUID id, @Param("revokedAt") Instant revokedAt, @Param("replacedBy") UUID replacedBy);
}
