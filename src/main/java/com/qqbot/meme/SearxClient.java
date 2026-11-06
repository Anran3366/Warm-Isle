package com.qqbot.meme;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 通过自建 SearXNG 搜索网页/图片（百度、必应、搜狗） */
@Service
public class SearxClient {

    private static final Logger log = LoggerFactory.getLogger(SearxClient.class);

    public record Result(String title, String url, String content, String imgSrc) {
    }

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public SearxClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    private String base() {
        String v = System.getenv("SEARXNG_URL");
        return v == null || v.isBlank() ? "http://searxng:8080" : v;
    }

    public List<Result> search(String query, boolean images) {
        List<Result> list = new ArrayList<>();
        try {
            String url = base() + "/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&format=json" + (images ? "&categories=images" : "");
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                log.warn("搜索失败 HTTP {}", resp.statusCode());
                return list;
            }
            JsonNode root = mapper.readTree(resp.body());
            for (JsonNode n : root.path("results")) {
                list.add(new Result(
                        n.path("title").asText(""),
                        n.path("url").asText(""),
                        n.path("content").asText(""),
                        n.path("img_src").asText("")));
            }
        } catch (Exception e) {
            log.warn("搜索异常: {}", e.getMessage());
        }
        return list;
    }

    public byte[] download(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() >= 300) {
                return null;
            }
            byte[] body = resp.body();
            return body == null || body.length > 3 * 1024 * 1024 ? null : body;
        } catch (Exception e) {
            return null;
        }
    }
}
