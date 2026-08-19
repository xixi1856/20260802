package com.blindway.media.infrastructure;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MediaMapper {

    @Insert(
            """
            INSERT INTO media_asset
                (id, owner_user_id, object_key, original_filename, content_type,
                 size_bytes, sha256, privacy_processed, created_at)
            VALUES
                (#{id}, #{ownerUserId}, #{objectKey}, #{originalFilename}, #{contentType},
                 #{sizeBytes}, #{sha256}, #{privacyProcessed}, #{createdAt})
            """)
    void insert(
            @Param("id") UUID id,
            @Param("ownerUserId") UUID ownerUserId,
            @Param("objectKey") String objectKey,
            @Param("originalFilename") String originalFilename,
            @Param("contentType") String contentType,
            @Param("sizeBytes") long sizeBytes,
            @Param("sha256") String sha256,
            @Param("privacyProcessed") boolean privacyProcessed,
            @Param("createdAt") Instant createdAt);

    @Select(
            """
            SELECT object_key
            FROM media_asset
            WHERE id = #{id} AND owner_user_id = #{ownerUserId}
            """)
    Optional<String> findOwnedObjectKey(@Param("id") UUID id, @Param("ownerUserId") UUID ownerUserId);
}
