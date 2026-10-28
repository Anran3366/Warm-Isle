package com.qqbot.api;

import com.qqbot.proactive.Followup;
import com.qqbot.proactive.ProactiveService;
import com.qqbot.proactive.ProactiveState;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 主动消息状态与调试接口（M5） */
@RestController
@RequestMapping("/api/proactive")
public class ProactiveController {

    private final ProactiveService service;

    public ProactiveController(ProactiveService service) {
        this.service = service;
    }

    @GetMapping("/state")
    public Map<String, Object> state(@RequestParam long qqId) {
        ProactiveState st = service.state(qqId);
        List<Followup> followups = service.openFollowups(qqId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("qqId", qqId);
        body.put("suspended", st.suspended());
        body.put("unansweredCount", st.unansweredCount());
        body.put("grievanceLevel", st.grievanceLevel());
        body.put("teaseNext", st.teaseNext());
        body.put("lastUserAt", st.lastUserAt());
        body.put("lastProactiveAt", st.lastProactiveAt());
        body.put("followups", followups);
        return body;
    }

    /** 调试用：清空演示数据 */
    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestParam long qqId) {
        service.reset(qqId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("qqId", qqId);
        body.put("reset", true);
        return body;
    }

    /** 调试用：手动登记一条跟进（hours 表示几小时后到期） */
    @PostMapping("/followup")
    public Map<String, Object> followup(@RequestParam long qqId,
                                        @RequestParam(defaultValue = "感冒") String topic,
                                        @RequestParam(defaultValue = "2") int hours) {
        service.forceFollowup(qqId, topic, hours);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("qqId", qqId);
        body.put("topic", topic);
        body.put("hours", hours);
        return body;
    }

    /** 调试用：立刻进入停止打扰状态 */
    @PostMapping("/suspend")
    public Map<String, Object> suspend(@RequestParam long qqId) {
        service.forceSuspend(qqId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("qqId", qqId);
        body.put("suspended", true);
        return body;
    }

    /**
     * 立即发一条主动消息，忽略静默时段与频率限制（调试用）。
     * type=followup（默认）或 nudge
     */
    @PostMapping("/trigger")
    public Map<String, Object> trigger(@RequestParam long qqId,
                                       @RequestParam(defaultValue = "followup") String type) {
        String content = service.forceSend(qqId, type);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("qqId", qqId);
        body.put("sent", content != null);
        body.put("content", content);
        return body;
    }
}
