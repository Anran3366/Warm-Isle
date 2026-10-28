package com.qqbot.api;

import com.qqbot.privacy.UserDataService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 隐私：删除某个 QQ 的全部聊天与记忆数据 */
@RestController
@RequestMapping("/api/privacy")
public class PrivacyController {

    private final UserDataService users;

    public PrivacyController(UserDataService users) {
        this.users = users;
    }

    @DeleteMapping("/users/{qqId}")
    public Map<String, Object> delete(@PathVariable long qqId) {
        int rows = users.deleteAll(qqId);
        return Map.of("ok", true, "qqId", qqId, "deletedRows", rows);
    }
}
