package com.qqbot.plugin.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.plugin.PluginProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * 索引的本地缓存（设计文档 §7.5）。
 *
 * <p>过期后**先返回旧值、后台再刷新**（stale-while-revalidate），商店挂掉不影响
 * 已装插件的任何行为；进程启动时也不拉索引，避免把商店拖进启动路径。
 */
@Component
public class StoreCache {

    private static final Logger log = LoggerFactory.getLogger(StoreCache.class);

    private final ObjectMapper mapper;
    private final Path file;
    private final long ttlMillis;

    private volatile StoreIndex last;
    private volatile long lastFetchAt = 0L;

    public StoreCache(PluginProperties props, ObjectMapper mapper) {
        this.mapper = mapper;
        Path dataDir = Path.of(props.getDir()).toAbsolutePath().normalize().getParent();
        if (dataDir == null) {
            dataDir = Path.of("./data").toAbsolutePath().normalize();
        }
        this.file = dataDir.resolve("cache").resolve("store-index.json");
        this.ttlMillis = Duration.ofSeconds(Math.max(props.getStore().getIndexTtlSeconds(), 60)).toMillis();
    }

    public Path file() {
        return file;
    }

    /** 内存命中优先，其次读磁盘；都没有返回 null。 */
    public StoreIndex current() {
        StoreIndex mem = last;
        if (mem != null) {
            return mem;
        }
        try {
            if (Files.isRegularFile(file)) {
                StoreIndex fromDisk = mapper.readValue(Files.readString(file), StoreIndex.class);
                last = fromDisk;
                return fromDisk;
            }
        } catch (Exception e) {
            log.warn("读取商店索引缓存失败: {}", e.getMessage());
        }
        return null;
    }

    public boolean fresh() {
        return last != null && System.currentTimeMillis() - lastFetchAt < ttlMillis;
    }

    public void save(StoreIndex index) {
        last = index;
        lastFetchAt = System.currentTimeMillis();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("store-index.json.tmp");
            Files.writeString(tmp, mapper.writeValueAsString(index));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            log.warn("写商店索引缓存失败: {}", e.getMessage());
        }
    }

    /** 手动刷新（后台「刷新」按钮）：清掉时间戳，下次取就重新拉。 */
    public void invalidate() {
        lastFetchAt = 0L;
    }

    public long ageMillis() {
        return lastFetchAt == 0 ? -1 : System.currentTimeMillis() - lastFetchAt;
    }
}
