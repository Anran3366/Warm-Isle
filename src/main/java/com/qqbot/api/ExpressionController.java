package com.qqbot.api;

import com.qqbot.expression.ExpressionConfig;
import com.qqbot.expression.FriendCategoryService;
import com.qqbot.expression.QzoneExpressionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 人格表达：说说受众、发布历史、好友自动分类 */
@RestController
@RequestMapping("/api")
public class ExpressionController {

    private final ExpressionConfig config;
    private final QzoneExpressionService qzone;
    private final FriendCategoryService friends;
    private final UserResolver users;

    public ExpressionController(ExpressionConfig config, QzoneExpressionService qzone,
                                FriendCategoryService friends, UserResolver users) {
        this.config = config;
        this.qzone = qzone;
        this.friends = friends;
        this.users = users;
    }

    @GetMapping("/expression/config")
    public Map<String, Object> getConfig() {
        return config.snapshot();
    }

    @PutMapping("/expression/config")
    public Map<String, Object> updateConfig(@RequestBody(required = false) Map<String, Object> body) {
        return config.update(body);
    }

    @PostMapping("/expression/qzone/draft")
    public Map<String, Object> draft(@RequestBody(required = false) Map<String, Object> body) {
        long qqId = users.resolve(longValue(body, "qqId"));
        String hint = body == null || body.get("hint") == null ? null : String.valueOf(body.get("hint"));
        try {
            return qzone.draft(qqId, hint);
        } catch (Exception e) {
            return error("DRAFT_FAILED", e.getMessage());
        }
    }

    @PostMapping("/expression/qzone/publish")
    public Map<String, Object> publish(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null || body.get("content") == null || String.valueOf(body.get("content")).isBlank()) {
            return error("INVALID_CONTENT", "说说内容不能为空");
        }
        long qqId = users.resolve(longValue(body, "qqId"));
        String content = String.valueOf(body.get("content")).trim();
        String audience = body.get("audience") == null ? null : String.valueOf(body.get("audience"));
        List<String> images = new ArrayList<>();
        if (body.get("images") instanceof List<?> list) {
            for (Object image : list) {
                if (image != null && !String.valueOf(image).isBlank()) {
                    images.add(String.valueOf(image));
                }
            }
        }
        try {
            return qzone.publish(content, qqId, audience, images);
        } catch (IllegalArgumentException e) {
            return error("INVALID_CONTENT", e.getMessage());
        } catch (Exception e) {
            return error("PUBLISH_FAILED", e.getMessage());
        }
    }

    @GetMapping("/expression/qzone/drafts")
    public Map<String, Object> drafts() {
        try {
            return qzone.drafts();
        } catch (Exception e) {
            return error("DRAFTS_FAILED", e.getMessage());
        }
    }

    @PutMapping("/expression/qzone/drafts/{id}/audience")
    public Map<String, Object> updateDraftAudience(@PathVariable long id,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        String audience = body == null || body.get("audience") == null
                ? "auto" : String.valueOf(body.get("audience"));
        try {
            return qzone.updateDraftAudience(id, audience);
        } catch (Exception e) {
            return error("DRAFT_UPDATE_FAILED", e.getMessage());
        }
    }

    @PostMapping("/expression/qzone/drafts/{id}/publish")
    public Map<String, Object> publishDraft(@PathVariable long id) {
        try {
            return qzone.publishDraft(id);
        } catch (IllegalArgumentException e) {
            return error("INVALID_CONTENT", e.getMessage());
        } catch (Exception e) {
            return error("PUBLISH_FAILED", e.getMessage());
        }
    }

    @DeleteMapping("/expression/qzone/drafts/{id}")
    public Map<String, Object> deleteDraft(@PathVariable long id) {
        return qzone.deleteDraft(id);
    }

    @GetMapping("/expression/qzone/history")
    public Map<String, Object> history(@RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        try {
            return qzone.history(page, size);
        } catch (Exception e) {
            return error("HISTORY_FAILED", e.getMessage());
        }
    }

    @GetMapping("/friends")
    public Map<String, Object> friends(@RequestParam(required = false) String category,
                                       @RequestParam(required = false) String q) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", friends.list(category, q));
        body.put("counts", friends.counts());
        return body;
    }

    @PostMapping("/friends/refresh")
    public Map<String, Object> refreshFriends() {
        try {
            return friends.refresh();
        } catch (Exception e) {
            return error("FRIEND_REFRESH_FAILED", e.getMessage());
        }
    }

    private Long longValue(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(body.get(key)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Object> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message == null ? "操作失败" : message);
        return body;
    }
}
