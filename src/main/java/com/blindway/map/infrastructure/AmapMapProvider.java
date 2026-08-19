package com.blindway.map.infrastructure;

import com.blindway.common.api.ApiException;
import com.blindway.map.MapProvider;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class AmapMapProvider implements MapProvider {

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final AmapProperties properties;

    public AmapMapProvider(RestClient amapRestClient, ObjectMapper objectMapper, AmapProperties properties) {
        this.client = amapRestClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public ReverseGeocode reverseGeocode(double longitude, double latitude) {
        Coordinate gcj = convert(longitude, latitude);
        URI uri = uri("/v3/geocode/regeo")
                .queryParam("key", properties.webKey())
                .queryParam("location", gcj.longitude + "," + gcj.latitude)
                .queryParam("extensions", "base")
                .build()
                .encode()
                .toUri();
        JsonNode root = call(uri);
        String address = root.path("regeocode").path("formatted_address").asString();
        if (address == null || address.isBlank()) {
            throw unavailable("AMAP_EMPTY_GEOCODE", "高德未返回地址");
        }
        return new ReverseGeocode(address);
    }

    @Override
    public WalkingRoute walkingRoute(
            double originLongitude, double originLatitude, double destinationLongitude, double destinationLatitude) {
        Coordinate origin = convert(originLongitude, originLatitude);
        Coordinate destination = convert(destinationLongitude, destinationLatitude);
        URI uri = uri("/v3/direction/walking")
                .queryParam("key", properties.webKey())
                .queryParam("origin", origin.longitude + "," + origin.latitude)
                .queryParam("destination", destination.longitude + "," + destination.latitude)
                .build()
                .encode()
                .toUri();
        JsonNode root = call(uri);
        JsonNode path = root.path("route").path("paths").path(0);
        if (path.isMissingNode()) {
            throw unavailable("AMAP_NO_ROUTE", "高德未返回步行路线");
        }
        List<String> polylines = new ArrayList<>();
        for (JsonNode step : path.path("steps")) {
            String polyline = step.path("polyline").asString();
            if (polyline != null && !polyline.isBlank()) {
                polylines.add(polyline);
            }
        }
        return new WalkingRoute(integer(path, "distance"), integer(path, "duration"), String.join(";", polylines));
    }

    private Coordinate convert(double longitude, double latitude) {
        URI uri = uri("/v3/assistant/coordinate/convert")
                .queryParam("key", properties.webKey())
                .queryParam("locations", longitude + "," + latitude)
                .queryParam("coordsys", "gps")
                .build()
                .encode()
                .toUri();
        String[] values = call(uri).path("locations").asString().split(",");
        if (values.length != 2) {
            throw unavailable("AMAP_COORDINATE_CONVERSION_FAILED", "高德坐标转换失败");
        }
        try {
            return new Coordinate(Double.parseDouble(values[0]), Double.parseDouble(values[1]));
        } catch (NumberFormatException exception) {
            throw unavailable("AMAP_COORDINATE_CONVERSION_FAILED", "高德坐标转换失败");
        }
    }

    private JsonNode call(URI uri) {
        if (properties.webKey() == null || properties.webKey().isBlank()) {
            throw unavailable("AMAP_NOT_CONFIGURED", "高德Web服务Key尚未配置");
        }
        RestClientException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String body = client.get().uri(uri).retrieve().body(String.class);
                JsonNode root = objectMapper.readTree(body);
                if (!"1".equals(root.path("status").asString())) {
                    String infocode = root.path("infocode").asString();
                    throw unavailable("AMAP_" + safeCode(infocode), "高德服务调用失败");
                }
                return root;
            } catch (ApiException exception) {
                throw exception;
            } catch (RestClientResponseException exception) {
                last = exception;
                if (!exception.getStatusCode().is5xxServerError() || attempt == 1) {
                    break;
                }
            } catch (RestClientException exception) {
                last = exception;
                if (attempt == 1) {
                    break;
                }
            } catch (RuntimeException exception) {
                throw unavailable("AMAP_INVALID_RESPONSE", "高德返回了无法解析的数据");
            }
        }
        throw unavailable("AMAP_UNAVAILABLE", last == null ? "高德服务暂时不可用" : "高德服务暂时不可用");
    }

    private UriComponentsBuilder uri(String path) {
        return UriComponentsBuilder.fromUriString(properties.baseUrl()).path(path);
    }

    private int integer(JsonNode node, String field) {
        try {
            return Integer.parseInt(node.path(field).asString());
        } catch (NumberFormatException exception) {
            throw unavailable("AMAP_INVALID_RESPONSE", "高德返回了无法解析的数据");
        }
    }

    private String safeCode(String infocode) {
        return infocode == null || !infocode.matches("[0-9A-Za-z_-]{1,30}") ? "ERROR" : infocode;
    }

    private ApiException unavailable(String code, String detail) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, detail);
    }

    private record Coordinate(double longitude, double latitude) {}
}
