package com.blindway.media.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.blindway.common.api.ApiException;
import com.blindway.media.infrastructure.MediaMapper;
import com.blindway.media.infrastructure.MinioProperties;
import io.minio.MinioClient;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class MediaServiceTest {

    @Mock
    private MinioClient minio;

    @Mock
    private MediaMapper mapper;

    @Test
    void rejectsUploadWhenStorageIsDisabled() {
        MediaService service = new MediaService(minio, properties(false), mapper);
        var file = new MockMultipartFile("file", "test.png", "image/png", validPng());

        assertThatThrownBy(() -> service.upload(UUID.randomUUID(), file))
                .isInstanceOf(ApiException.class)
                .hasMessage("图片存储尚未启用");
    }

    @Test
    void rejectsContentWhoseSignatureDoesNotMatchType() {
        MediaService service = new MediaService(minio, properties(true), mapper);
        var file = new MockMultipartFile("file", "fake.png", "image/png", "not-a-png".getBytes());

        assertThatThrownBy(() -> service.upload(UUID.randomUUID(), file))
                .isInstanceOf(ApiException.class)
                .hasMessage("图片内容与声明类型不一致");

        verify(mapper, never())
                .insert(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        org.mockito.ArgumentMatchers.any());
    }

    private MinioProperties properties(boolean enabled) {
        return new MinioProperties(
                enabled,
                "http://localhost:9000",
                "access-key",
                "secret-key",
                "blindway-private",
                Duration.ofMinutes(5));
    }

    private byte[] validPng() {
        return new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    }
}
