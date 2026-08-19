package com.blindway.device.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DeviceMapper {

    @Insert(
            """
            INSERT INTO device
                (id, owner_user_id, label, secret_hash, status, last_seen_at,
                 software_version, model_version, created_at, updated_at)
            VALUES
                (#{id}, #{ownerUserId}, #{label}, #{secretHash}, #{status}, #{lastSeenAt},
                 #{softwareVersion}, #{modelVersion}, #{createdAt}, #{updatedAt})
            """)
    void insert(DeviceRow row);

    @Select(
            """
            SELECT id, owner_user_id, label, secret_hash, status, last_seen_at,
                   software_version, model_version, created_at, updated_at
            FROM device
            WHERE id = #{id}
            """)
    Optional<DeviceRow> findById(UUID id);

    @Select(
            """
            SELECT id, owner_user_id, label, secret_hash, status, last_seen_at,
                   software_version, model_version, created_at, updated_at
            FROM device
            WHERE owner_user_id = #{ownerUserId}
            ORDER BY created_at DESC
            """)
    List<DeviceRow> findByOwner(UUID ownerUserId);

    @Update(
            """
            UPDATE device
            SET owner_user_id = #{ownerUserId}, status = 'ACTIVE', updated_at = #{updatedAt}
            WHERE id = #{deviceId} AND owner_user_id IS NULL AND status = 'PROVISIONED'
            """)
    int bind(
            @Param("deviceId") UUID deviceId,
            @Param("ownerUserId") UUID ownerUserId,
            @Param("updatedAt") Instant updatedAt);

    @Update(
            """
            UPDATE device
            SET secret_hash = #{secretHash}, updated_at = #{updatedAt}
            WHERE id = #{deviceId} AND owner_user_id = #{ownerUserId} AND status = 'ACTIVE'
            """)
    int rotateSecret(
            @Param("deviceId") UUID deviceId,
            @Param("ownerUserId") UUID ownerUserId,
            @Param("secretHash") String secretHash,
            @Param("updatedAt") Instant updatedAt);

    @Update(
            """
            UPDATE device
            SET last_seen_at = #{occurredAt}, software_version = #{softwareVersion},
                model_version = #{modelVersion}, updated_at = #{updatedAt}
            WHERE id = #{deviceId} AND status <> 'DISABLED'
            """)
    int markSeen(
            @Param("deviceId") UUID deviceId,
            @Param("occurredAt") Instant occurredAt,
            @Param("softwareVersion") String softwareVersion,
            @Param("modelVersion") String modelVersion,
            @Param("updatedAt") Instant updatedAt);
}
