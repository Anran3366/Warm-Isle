package com.qqbot.api;

import com.qqbot.image.ImageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 图片理解的自测接口 */
@RestController
@RequestMapping("/api/image")
public class ImageController {

    private final ImageService imageService;

    public ImageController(ImageService imageService) {
        this.imageService = imageService;
    }

    /**
     * 传一个图片地址（http/https 或本地路径）直接跑一遍识别链路。
     */
    @GetMapping("/test")
    public Map<String, Object> test(@RequestParam String url,
                                    @RequestParam(defaultValue = "100000000") long qqId) {
        ImageService.Result r = imageService.describe(qqId, List.of(url));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", url);
        body.put("blocked", r.blocked());
        body.put("reason", r.reason());
        body.put("description", r.description());
        return body;
    }
}
