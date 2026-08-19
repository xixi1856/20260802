package com.blindway.media.api;

import com.blindway.common.security.CurrentUser;
import com.blindway.media.application.MediaService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/media")
public class MediaController {

    private final MediaService service;

    public MediaController(MediaService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    MediaResponse upload(@RequestPart("file") MultipartFile file, @AuthenticationPrincipal Jwt jwt) {
        return service.upload(CurrentUser.id(jwt), file);
    }

    @GetMapping("/{mediaId}/access-url")
    Map<String, String> accessUrl(@PathVariable UUID mediaId, @AuthenticationPrincipal Jwt jwt) {
        return Map.of("accessUrl", service.accessUrl(CurrentUser.id(jwt), mediaId));
    }
}
