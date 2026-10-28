package com.qqbot.api;

import com.qqbot.llm.PromptBuilder;
import com.qqbot.memory.Persona;
import com.qqbot.memory.PersonaStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** M3a 第 4 批：人设管理与 prompt 预览 */
@RestController
@RequestMapping("/api")
public class PersonaController {

    private final PersonaStore personas;
    private final PromptBuilder promptBuilder;

    public PersonaController(PersonaStore personas, PromptBuilder promptBuilder) {
        this.personas = personas;
        this.promptBuilder = promptBuilder;
    }

    @GetMapping("/personas")
    public List<Map<String, Object>> list(@RequestParam(required = false) Long botId) {
        return withBot(botId, () -> personas.list().stream().map(this::toJson).toList());
    }

    @PostMapping("/personas")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        String name = str(body.get("name"), "未命名");
        String content = str(body.get("content"), "");
        if (content.isBlank()) {
            return Map.of("code", "INVALID_ARGUMENT", "message", "人设内容不能为空");
        }
        long id = personas.create(name, content);
        Persona created = personas.byId(id);
        return created == null ? Map.of("ok", true) : toJson(created);
    }

    @PutMapping("/personas/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Persona existing = personas.byId(id);
        if (existing == null) {
            return Map.of("code", "PERSONA_NOT_FOUND", "message", "人设不存在");
        }
        String name = body.containsKey("name") ? str(body.get("name"), existing.name()) : existing.name();
        String content = body.containsKey("content") ? str(body.get("content"), existing.content()) : existing.content();
        personas.update(id, name, content);
        return Map.of("ok", true);
    }

    @DeleteMapping("/personas/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        personas.delete(id);
        return Map.of("ok", true);
    }

    @PostMapping("/personas/{id}/activate")
    public Map<String, Object> activate(@PathVariable long id,
                                        @RequestParam(required = false) Long botId) {
        return withBot(botId, () -> {
            if (personas.byId(id) == null) {
                return Map.of("code", "PERSONA_NOT_FOUND", "message", "人设不存在");
            }
            personas.activate(id);
            return Map.of("ok", true);
        });
    }

    /** 指定账号时，在对应账号上下文里执行（不同账号可以各用一份人设） */
    private <T> T withBot(Long botId, java.util.function.Supplier<T> action) {
        if (botId != null && botId > 0) {
            return com.qqbot.bot.BotContext.call(botId, action);
        }
        return action.get();
    }

    /** 系统提示词（只读）：固定规则 + 当前生效人设 */
    @GetMapping("/prompt/system")
    public Map<String, Object> systemPrompt() {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            var active = personas.active();
            body.put("persona", active == null ? "" : active.content());
            body.put("personaName", active == null ? "" : active.name());
            body.put("rules", PromptBuilder.staticRules());
            body.put("time", java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            body.put("ok", true);
        } catch (Exception e) {
            body.put("ok", false);
            body.put("message", e.getMessage());
        }
        return body;
    }

    /** 最近一次回复实际使用的 prompt 分段 */
    @GetMapping("/prompt/preview")
    public Map<String, Object> preview() {
        PromptBuilder.LastPrompt p = promptBuilder.preview();
        if (p.totalTokens() <= 0) {
            // 服务刚重启还没真实对话：用当前人设拼一个示意预览
            String persona = "";
            try {
                var active = personas.active();
                persona = active != null && active.content() != null ? active.content() : "";
            } catch (Exception ignored) {
                // 读不到就用空
            }
            Map<String, Object> demo = new LinkedHashMap<>();
            demo.put("persona", persona);
            demo.put("facts", "（还没有对话，暂未积累画像）");
            demo.put("memories", "");
            demo.put("history", "");
            demo.put("user", "（示例消息）在吗");
            demo.put("totalTokens", Math.max(1, (persona.length() + 12) / 4));
            demo.put("demo", true);
            return demo;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("persona", p.persona());
        body.put("facts", p.factsBlock());
        body.put("memories", p.memories());
        body.put("history", p.history());
        body.put("user", p.user());
        body.put("totalTokens", p.totalTokens());
        return body;
    }

    private Map<String, Object> toJson(Persona p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(p.id()));
        m.put("name", p.name());
        m.put("content", p.content());
        m.put("active", p.active());
        return m;
    }

    private String str(Object v, String fallback) {
        return v == null ? fallback : String.valueOf(v);
    }
}
