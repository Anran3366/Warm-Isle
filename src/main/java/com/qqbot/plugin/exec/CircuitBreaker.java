package com.qqbot.plugin.exec;

import com.qqbot.plugin.PluginProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 熔断：60 秒内失败 N 次 → 打开 M 分钟；半开时放 1 个探针。
 * 状态只在内存里，重启即清零（插件本来就不该有持久化故障状态）。
 */
@Component
public class CircuitBreaker {

    private static final long WINDOW_MS = 60_000;

    private static final class State {
        int failures;
        long windowStart;
        long openUntil;
        boolean probeInFlight;
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final PluginProperties props;

    public CircuitBreaker(PluginProperties props) {
        this.props = props;
    }

    public boolean allow(String pluginId) {
        State s = states.computeIfAbsent(pluginId, k -> new State());
        synchronized (s) {
            long now = System.currentTimeMillis();
            if (s.openUntil > now) {
                return false;
            }
            if (s.openUntil != 0) {
                // 半开：只放一个探针
                if (s.probeInFlight) {
                    return false;
                }
                s.probeInFlight = true;
                return true;
            }
            return true;
        }
    }

    public void success(String pluginId) {
        State s = states.computeIfAbsent(pluginId, k -> new State());
        synchronized (s) {
            s.failures = 0;
            s.openUntil = 0;
            s.probeInFlight = false;
        }
    }

    public void failure(String pluginId) {
        State s = states.computeIfAbsent(pluginId, k -> new State());
        synchronized (s) {
            long now = System.currentTimeMillis();
            if (now - s.windowStart > WINDOW_MS) {
                s.windowStart = now;
                s.failures = 0;
            }
            s.failures++;
            s.probeInFlight = false;
            if (s.failures >= props.getLimits().getCircuitFailures()) {
                s.openUntil = now + props.getLimits().getCircuitOpenSeconds() * 1000L;
            }
        }
    }

    public boolean isOpen(String pluginId) {
        State s = states.get(pluginId);
        if (s == null) {
            return false;
        }
        synchronized (s) {
            return s.openUntil > System.currentTimeMillis();
        }
    }
}
