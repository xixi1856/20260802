package com.blindway.media.application;

import com.blindway.common.api.ApiException;
import com.blindway.media.api.MediaResponse;
import com.blindway.media.infrastructure.MediaMapper;
import com.blindway.media.infrastructure.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MediaService {

    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Map<String, String> EXTENSIONS =
            Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");

    private final MinioClient minio;
    private final MinioProperties properties;
    private final MediaMapper mapper;

    public MediaService(MinioClient minio, MinioProperties properties, MediaMapper mapper) {
        this.minio = minio;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Transactional
    public MediaResponse upload(UUID ownerUserId, MultipartFile file) {
        if (!properties.enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_STORAGE_DISABLED", "图片存储尚未启用");
        }
        byte[] bytes = readAndValidate(file);
        String contentType = file.getContentType();
        UUID id = UUID.randomUUID();
        String objectKey = ownerUserId + "/" + id + "." + EXTENSIONS.get(contentType);
        try {
            ensureBucket();
            minio.putObject(
                    PutObjectArgs.builder()
                            .bucket(properties.bucket())
                            .object(objectKey)
                            .contentType(contentType)
                            .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                            .build());
            mapper.insert(
                    id,
                    ownerUserId,
                    objectKey,
                    safeFilename(file.getOriginalFilename()),
                    contentType,
                    bytes.length,
                    sha256(bytes),
                    false,
                    Instant.now());
            return new MediaResponse(id, contentType, bytes.length, presignedUrl(objectKey));
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_STORAGE_UNAVAILABLE", "图片存储暂时不可用");
        }
    }

    @Transactional(readOnly = true)
    public String accessUrl(UUID ownerUserId, UUID mediaId) {
        String objectKey = mapper.findOwnedObjectKey(mediaId, ownerUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND", "图片不存在"));
        return presignedUrl(objectKey);
    }

    private byte[] readAndValidate(MultipartFile file) {
        if (file.isEmpty() || file.getSize() > MAX_BYTES || !ALLOWED_TYPES.contains(file.getContentType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MEDIA", "仅支持不超过5MB的JPEG、PNG或WebP图片");
        }
        try {
            byte[] bytes = file.getBytes();
            if (!matchesMagic(bytes, file.getContentType())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MEDIA_SIGNATURE", "图片内容与声明类型不一致");
            }
            return bytes;
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MEDIA", "无法读取上传图片");
        }
    }

    private boolean matchesMagic(byte[] bytes, String contentType) {
        if ("image/jpeg".equals(contentType)) {
            return bytes.length >= 3
                    && (bytes[0] & 0xff) == 0xff
                    && (bytes[1] & 0xff) == 0xd8
                    && (bytes[2] & 0xff) == 0xff;
        }
        if ("image/png".equals(contentType)) {
            return bytes.length >= 8
                    && (bytes[0] & 0xff) == 0x89
                    && bytes[1] == 0x50
                    && bytes[2] == 0x4e
                    && bytes[3] == 0x47;
        }
        return bytes.length >= 12
                && "RIFF".equals(new String(bytes, 0, 4, StandardCharsets.US_ASCII))
                && "WEBP".equals(new String(bytes, 8, 4, StandardCharsets.US_ASCII));
    }

    private void ensureBucket() throws Exception {
        if (!minio.bucketExists(
                BucketExistsArgs.builder().bucket(properties.bucket()).build())) {
            minio.makeBucket(
                    MakeBucketArgs.builder().bucket(properties.bucket()).build());
        }
    }

    private String presignedUrl(String objectKey) {
        try {
            return minio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .expiry((int) properties.presignedUrlTtl().toSeconds())
                    .build());
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_STORAGE_UNAVAILABLE", "图片访问地址暂不可用");
        }
    }

    private String safeFilename(String original) {
        if (original == null || original.isBlank()) {
            return "upload";
        }
        String normalized = original.replace('\\', '/');
        String filename = normalized.substring(normalized.lastIndexOf('/') + 1);
        return filename.length() > 255 ? filename.substring(filename.length() - 255) : filename;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
