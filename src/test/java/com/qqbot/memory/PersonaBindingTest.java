package com.qqbot.memory;

import com.qqbot.bot.BotContext;
import com.qqbot.config.BotProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.*;

class PersonaBindingTest {

    @TempDir
    Path tmp;

    @Test
    void bindingOnlyAffectsTargetUserAndFallsBack() throws Exception {
        BotProperties props = new BotProperties();
        props.getDb().setPath(tmp.resolve("data/qqbot.db").toString());
        GlobalDb global = new GlobalDb(props);
        global.init();
        Db db = new Db(props, global);
        db.init();
        db.open(1);
        SharedDb shared = new SharedDb(props);
        shared.init();
        PersonaStore personas = new PersonaStore(shared, db, new SettingStore(db), props);

        long globalId = personas.create("小满", "【身份】小满");
        long importedId = personas.create("林深", "【身份】林深");
        personas.activate(globalId);

        BotContext.run(1L, () -> {
            personas.bind(1L, 100000000L, importedId, null);
            assertEquals(importedId, personas.activeFor(100000000L).id());
            assertEquals(globalId, personas.activeFor(200000000L).id(), "别的 QQ 仍用全局");
            personas.setBindingEnabled(1L, 100000000L, false);
            assertEquals(globalId, personas.activeFor(100000000L).id(), "关闭后回退全局");
        });
    }

    @Test
    void pluginBindingIsIgnoredWhenPluginDisabled() throws Exception {
        BotProperties props = new BotProperties();
        props.getDb().setPath(tmp.resolve("data/qqbot.db").toString());
        GlobalDb global = new GlobalDb(props);
        global.init();
        Db db = new Db(props, global);
        db.init();
        db.open(1);
        SharedDb shared = new SharedDb(props);
        shared.init();
        PersonaStore personas = new PersonaStore(shared, db, new SettingStore(db), props);

        long globalId = personas.create("小满", "【身份】小满");
        long importedId = personas.create("林深", "【身份】林深");
        personas.activate(globalId);
        BotContext.run(1L, () -> {
            personas.bind(1L, 100000000L, importedId, "menran.deepseek-import");
            assertEquals(globalId, personas.activeFor(100000000L).id(), "插件未启用时不能用导入人设");
        });

        try (Connection c = db.open(1);
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO plugin_state(plugin_id, enabled, updated_at) VALUES(?,1,datetime('now'))")) {
            ps.setString(1, "menran.deepseek-import");
            ps.executeUpdate();
        }
        BotContext.run(1L, () ->
                assertEquals(importedId, personas.activeFor(100000000L).id(), "插件启用后恢复绑定"));
    }
}
