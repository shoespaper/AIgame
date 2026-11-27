package com.gameneko.aigame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gameneko.aigame.engine.GameInstance;
import com.gameneko.aigame.engine.CharacterProfile;
import com.gameneko.aigame.engine.WorldState;
import com.gameneko.aigame.persistence.FileGameRepository;
import com.gameneko.aigame.infrastructure.ClasspathGameStateTypeResolver;
import com.gameneko.aigame.application.port.GamePersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileGameRepositoryTests {
    @TempDir
    Path saveRoot;

    @Test
    void savesAndLoadsGameInstance() {
        FileGameRepository repository = new FileGameRepository(new ObjectMapper(), saveRoot, new ClasspathGameStateTypeResolver());
        WorldState state = state();
        GameInstance<?> instance = GameInstance.narrativeOnly("demo", state, new CharacterProfile("林舟", 28, "未指定", "商队向导"));

        repository.save(instance);

        GameInstance<?> loaded = repository.load("demo").orElseThrow();
        assertEquals(instance.gameId(), loaded.gameId());
        assertEquals(instance.worldId(), loaded.worldId());
        assertEquals(instance.rulesetId(), loaded.rulesetId());
        assertEquals(instance.lifecycle(), loaded.lifecycle());
        assertEquals(state, loaded.worldState());
        assertEquals(instance.character(), loaded.character());
        assertTrue(Files.exists(saveRoot.resolve("demo.json")));
    }

    /** 直接读取旧格式会失败，列表仍能显示当前格式存档。 */
    @Test
    void rejectsPreviousEnvelopeWithoutCharacterProfile() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        FileGameRepository repository = new FileGameRepository(mapper, saveRoot, new ClasspathGameStateTypeResolver());
        GameInstance<?> instance = GameInstance.narrativeOnly("legacy", state());
        ObjectNode outdated = mapper.createObjectNode();
        outdated.put("schemaVersion", 2);
        outdated.put("gameId", "legacy");
        ObjectNode state = mapper.valueToTree(instance);
        state.remove("character");
        outdated.set("state", state);
        Files.createDirectories(saveRoot);
        Files.writeString(saveRoot.resolve("legacy.json"), mapper.writeValueAsString(outdated));

        assertThrows(GamePersistenceException.class, () -> repository.load("legacy"));
        repository.save(GameInstance.narrativeOnly("current", instance.worldState()));
        assertEquals(List.of("current"), repository.list().stream().map(save -> save.instance().gameId()).toList());
        assertTrue(Files.exists(saveRoot.resolve("legacy.json")));
    }

    @Test
    void returnsEmptyForMissingSave() {
        FileGameRepository repository = new FileGameRepository(new ObjectMapper(), saveRoot, new ClasspathGameStateTypeResolver());
        assertTrue(repository.load("missing").isEmpty());
    }

    @Test
    void rejectsTraversalAndCorruptSave() throws Exception {
        FileGameRepository repository = new FileGameRepository(new ObjectMapper(), saveRoot, new ClasspathGameStateTypeResolver());
        assertThrows(IllegalArgumentException.class, () -> repository.load("../outside"));

        Files.createDirectories(saveRoot);
        Files.writeString(saveRoot.resolve("broken.json"), "{\"schemaVersion\":99}");
        assertThrows(GamePersistenceException.class, () -> repository.load("broken"));
    }

    private static WorldState state() {
        return new WorldState("isekai_grey", "1.0", 0, "灰烬界", null,
                "白栎镇", "forest", null, 1, "清晨",
                Map.of(), "", List.of(), List.of(), Map.of(), List.of());
    }
}
