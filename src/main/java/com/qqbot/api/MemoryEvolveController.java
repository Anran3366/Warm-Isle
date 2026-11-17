package com.qqbot.api;

import com.qqbot.memory.SettingStore;
import com.qqbot.memory.evolve.ExpressionMiner;
import com.qqbot.memory.evolve.ExpressionService;
import com.qqbot.memory.evolve.GrowthService;
import com.qqbot.memory.evolve.MemoryAuditService;
import com.qqbot.memory.evolve.MemoryConsolidator;
import com.qqbot.memory.evolve.MemoryReflector;
import com.qqbot.memory.evolve.MemoryTimelineService;
import com.qqbot.memory.evolve.PersonProfileService;
import com.qqbot.memory.evolve.ProfileSnapshotService;
import com.qqbot.memory.evolve.RecallPlanner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 记忆自进化后台：成长档案 / 表达方式 / 体检 / 手动触发。 */
@RestController
@RequestMapping("/api/memory/evolve")
public class MemoryEvolveController {

    private final ProfileSnapshotService profile;
    private final RecallPlanner recallPlanner;
    private final MemoryReflector reflector;
    private final MemoryConsolidator consolidator;
    private final MemoryAuditService audit;
    private final GrowthService growth;
    private final ExpressionService expressions;
    private final ExpressionMiner miner;
    private final PersonProfileService personProfile;
    private final MemoryTimelineService timeline;
    private final SettingStore settings;

    public MemoryEvolveController(ProfileSnapshotService profile, RecallPlanner recallPlanner,
                                  MemoryReflector reflector, MemoryConsolidator consolidator,
                                  MemoryAuditService audit, GrowthService growth,
                                  ExpressionService expressions, ExpressionMiner miner,
                                  PersonProfileService personProfile, MemoryTimelineService timeline,
                                  SettingStore settings) {
        this.profile = profile;
        this.recallPlanner = recallPlanner;
        this.reflector = reflector;
        this.consolidator = consolidator;
        this.audit = audit;
        this.growth = growth;
        this.expressions = expressions;
        this.miner = miner;
        this.personProfile = personProfile;
        this.timeline = timeline;
        this.settings = settings;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", !"false".equalsIgnoreCase(settings.get("memory.evolve.enabled", "true")));
        return out;
    }

    @PostMapping("/toggle")
    public Map<String, Object> toggle(@RequestParam boolean enabled) {
        settings.set("memory.evolve.enabled", String.valueOf(enabled));
        return status();
    }

    @GetMapping("/snapshot")
    public Map<String, Object> snapshot(@RequestParam long qqId) {
        return Map.of("text", profile.snapshot(qqId));
    }

    @PostMapping("/snapshot/rebuild")
    public Map<String, Object> rebuild(@RequestParam long qqId) {
        return Map.of("text", profile.rebuild(qqId));
    }

    @GetMapping("/recall")
    public Map<String, Object> recall(@RequestParam long qqId,
                                      @RequestParam(defaultValue = "在吗") String text) {
        return Map.of("block", recallPlanner.planBlock(qqId, text));
    }

    @GetMapping("/profile")
    public Map<String, Object> profile(@RequestParam long qqId) {
        return personProfile.get(qqId);
    }

    @PostMapping("/profile")
    public Map<String, Object> saveProfile(@RequestParam long qqId,
                                           @RequestBody Map<String, Object> body) {
        return personProfile.save(qqId,
                string(body.get("address")),
                string(body.get("stage")),
                strings(body.get("traits")),
                strings(body.get("taboos")),
                dates(body.get("importantDates")));
    }

    @PostMapping("/profile/backfill")
    public Map<String, Object> backfillProfile(@RequestParam long qqId) {
        int changed = personProfile.backfill(qqId);
        return Map.<String, Object>of("updated", changed, "profile", personProfile.get(qqId));
    }

    @GetMapping("/timeline")
    public Map<String, Object> timeline(@RequestParam long qqId,
                                        @RequestParam(defaultValue = "100") int limit) {
        return Map.of("items", timeline.timeline(qqId, limit));
    }

    @PostMapping("/reflect")
    public Map<String, Object> reflect(@RequestParam long qqId,
                                       @RequestParam(defaultValue = "true") boolean force) {
        return reflector.reflect(qqId, force);
    }

    @PostMapping("/consolidate")
    public Map<String, Object> consolidate(@RequestParam long qqId) {
        return consolidator.run(qqId);
    }

    @GetMapping("/growth")
    public Map<String, Object> growth(@RequestParam long qqId,
                                      @RequestParam(required = false) String status) {
        return Map.of("items", growth.list(qqId, status, 100));
    }

    @PostMapping("/growth/{id}/approve")
    public Map<String, Object> approveGrowth(@PathVariable long id) {
        return Map.of("updated", growth.review(id, true));
    }

    @PostMapping("/growth/{id}/reject")
    public Map<String, Object> rejectGrowth(@PathVariable long id) {
        return Map.of("updated", growth.review(id, false));
    }

    @GetMapping("/expressions")
    public Map<String, Object> expressions(@RequestParam long qqId,
                                           @RequestParam(required = false) String status) {
        return Map.of("items", expressions.list(qqId, status, 200));
    }

    @PostMapping("/expressions/{id}/approve")
    public Map<String, Object> approveExpression(@PathVariable long id) {
        return Map.of("updated", expressions.review(id, true));
    }

    @PostMapping("/expressions/{id}/reject")
    public Map<String, Object> rejectExpression(@PathVariable long id) {
        return Map.of("updated", expressions.review(id, false));
    }

    @PostMapping("/expressions/mine")
    public Map<String, Object> mine(@RequestParam long qqId,
                                    @RequestParam(defaultValue = "7") int days) {
        return Map.<String, Object>of("added", miner.mine(qqId, days));
    }

    @GetMapping("/audit")
    public Map<String, Object> audit(@RequestParam long qqId) {
        return audit.audit(qqId);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static java.util.List<String> strings(Object value) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (value instanceof java.util.List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }

    private static java.util.List<Map<String, String>> dates(Object value) {
        java.util.List<Map<String, String>> out = new java.util.ArrayList<>();
        if (value instanceof java.util.List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object md = map.get("md");
                    Object label = map.get("label");
                    Map<String, String> one = new LinkedHashMap<>();
                    one.put("md", md == null ? "" : String.valueOf(md));
                    one.put("label", label == null ? "" : String.valueOf(label));
                    if (!one.get("md").isBlank()) {
                        out.add(one);
                    }
                }
            }
        }
        return out;
    }
}
