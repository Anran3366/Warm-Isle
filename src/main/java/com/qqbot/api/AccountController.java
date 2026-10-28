package com.qqbot.api;

import com.qqbot.account.AccountService;
import com.qqbot.account.ProtocolBackends;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 机器人账号：列表、新建实例、二维码、下线 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final AccountService account;
    private final ProtocolBackends backends;
    private final com.qqbot.settings.WhitelistService whitelistService;
    private final com.qqbot.profile.BotProfileService profileService;

    public AccountController(AccountService account, ProtocolBackends backends,
                             com.qqbot.settings.WhitelistService whitelistService,
                             com.qqbot.profile.BotProfileService profileService) {
        this.account = account;
        this.backends = backends;
        this.whitelistService = whitelistService;
        this.profileService = profileService;
    }

    /** 可选的接入方式（前端新建账号时的下拉） */
    @GetMapping("/backends")
    public Map<String, Object> backends() {
        return Map.of("items", backends.describe());
    }

    @GetMapping("/list")
    public Map<String, Object> list() throws Exception {
        List<Map<String, Object>> items = account.list();
        return Map.of("items", items);
    }

    /** 新建一个独立实例（不碰现有账号）；backend 可选 napcat / snowluma */
    @PostMapping("/create")
    public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> body) throws Exception {
        String name = body == null ? null : String.valueOf(body.getOrDefault("name", ""));
        String backend = body == null ? null : String.valueOf(body.getOrDefault("backend", ""));
        Long migrateFrom = null;
        if (body != null && body.get("migrateFrom") instanceof Number n && n.longValue() > 0) {
            migrateFrom = n.longValue();
        }
        boolean copyOnly = body != null && Boolean.TRUE.equals(body.get("copyOnly"));
        return account.create(name, backend, migrateFrom, copyOnly);
    }

    /** 切换接入方式（只重建协议端容器，聊天数据保留） */
    @PostMapping("/{id}/backend")
    public Map<String, Object> switchBackend(@PathVariable long id,
                                             @RequestBody(required = false) Map<String, Object> body)
            throws Exception {
        String backend = body == null ? null : String.valueOf(body.getOrDefault("backend", ""));
        return account.switchBackend(id, backend);
    }

    @GetMapping(value = "/{id}/qrcode", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qrcode(@PathVariable long id) throws Exception {
        byte[] png = account.qrcode(id);
        if (png == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .header("Cache-Control", "no-store").body(png);
    }

    /** 主人 QQ：只有这个号能让机器人改头像/昵称 */
    @GetMapping("/{id}/owner")
    public Map<String, Object> owner(@PathVariable long id) {
        return com.qqbot.bot.BotContext.call(id, () -> Map.of("qqId", profileService.ownerQq()));
    }

    @PutMapping("/{id}/owner")
    public Map<String, Object> updateOwner(@PathVariable long id,
                                           @RequestBody(required = false) Map<String, Object> body) {
        long qq = 0;
        if (body != null && body.get("qqId") instanceof Number n) {
            qq = n.longValue();
        }
        long finalQq = qq;
        return com.qqbot.bot.BotContext.call(id, () -> {
            profileService.setOwner(finalQq);
            return Map.of("ok", true, "qqId", profileService.ownerQq());
        });
    }

    /** 机器人形象：当前昵称 + 修改历史 */
    @GetMapping("/{id}/profile")
    public Map<String, Object> profile(@PathVariable long id) {
        return com.qqbot.bot.BotContext.call(id, () -> {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("ownerQq", profileService.ownerQq());
            body.put("history", profileService.history(20));
            com.qqbot.bot.OneBotClient client = account.client(id);
            body.put("nickname", client == null ? "" : client.selfNickname());
            return body;
        });
    }

    @PostMapping("/{id}/profile/restore")
    public Map<String, Object> restoreProfile(@PathVariable long id,
                                              @RequestBody(required = false) Map<String, Object> body) {
        long logId = 0;
        if (body != null && body.get("logId") instanceof Number n) {
            logId = n.longValue();
        }
        long finalLogId = logId;
        return com.qqbot.bot.BotContext.call(id, () -> profileService.restore(finalLogId));
    }

    /** 该机器人账号的好友列表（用于可聊天 QQ 选择） */
    @GetMapping("/{id}/friends")
    public Map<String, Object> friends(@PathVariable long id) {
        return com.qqbot.bot.BotContext.call(id, () -> {
            com.qqbot.bot.OneBotClient client = account.client(id);
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            if (client == null || !client.isConnected()) {
                body.put("ok", false);
                body.put("message", "该机器人账号未在线");
                body.put("items", java.util.List.of());
                return body;
            }
            try {
                body.put("ok", true);
                body.put("items", client.getFriendList());
            } catch (Exception e) {
                body.put("ok", false);
                body.put("message", e.getMessage());
                body.put("items", java.util.List.of());
            }
            return body;
        });
    }

    /** 刷新登录二维码（账号离线时重启该实例，重新生成二维码） */
    @PostMapping("/{id}/qrcode/refresh")
    public Map<String, Object> refreshQrcode(@PathVariable long id) throws Exception {
        return account.refreshQrcode(id);
    }

    /** 临时调试：直接调 NapCat 的 OneBot action */
    @GetMapping("/{id}/debug/action")
    public Map<String, Object> debugAction(@PathVariable long id, @RequestParam String action) {
        return com.qqbot.bot.BotContext.call(id, () -> {
            com.qqbot.bot.OneBotClient client = account.client(id);
            if (client == null) {
                return Map.of("ok", false, "message", "未连接");
            }
            try {
                var node = client.callAction(action, null, 8000);
                return Map.of("ok", true, "data", node.toString());
            } catch (Exception e) {
                return Map.of("ok", false, "message", e.getMessage());
            }
        });
    }
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id,
                                      @RequestParam(defaultValue = "false") boolean keepData) throws Exception {
        return account.delete(id, keepData);
    }

    @PostMapping("/{id}/logout")
    public Map<String, Object> logout(@PathVariable long id) throws Exception {
        return account.logout(id);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) throws Exception {
        return account.get(id);
    }

    /** 该机器人账号「可聊天的 QQ」 */
    @GetMapping("/{id}/whitelist")
    public Map<String, Object> whitelist(@PathVariable long id) {
        return com.qqbot.bot.BotContext.call(id, () -> {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("ids", whitelistService.ids());
            body.put("allAllowed", whitelistService.ids().isEmpty());
            return body;
        });
    }

    @PutMapping("/{id}/whitelist")
    public Map<String, Object> updateWhitelist(@PathVariable long id,
                                               @RequestBody Map<String, Object> body) {
        return com.qqbot.bot.BotContext.call(id, () -> {
            java.util.List<Long> ids = new java.util.ArrayList<>();
            Object raw = body == null ? null : body.get("ids");
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    try {
                        ids.add(Long.parseLong(String.valueOf(o).trim()));
                    } catch (NumberFormatException ignored) {
                        // 跳过非法项
                    }
                }
            }
            whitelistService.set(ids);
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("ids", whitelistService.ids());
            out.put("allAllowed", whitelistService.ids().isEmpty());
            return out;
        });
    }
}
