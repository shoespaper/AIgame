package com.gameneko.aigame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.engine.GameInstance;
import com.gameneko.aigame.engine.WorldState;
import com.gameneko.aigame.persistence.FileGameRepository;
import com.gameneko.aigame.persistence.GamePersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileGameRepositoryTests {
    @TempDir
    Path saveRoot;

    @Test
    void savesAndLoadsGameInstance() {
        FileGameRepository repository = new FileGameRepository(new ObjectMapper(), saveRoot, new com.gameneko.aigame.infrastructure.ClasspathGameStateTypeResolver());
        WorldState state = state();
        GameInstance<?> instance = GameInstance.narrativeOnly("demo", state);

        repository.save(instance);

        GameInstance<?> loaded = repository.load("demo").orElseThrow();
        assertEquals(instance.gameId(), loaded.gameId());
        assertEquals(instance.worldId(), loaded.worldId());
        assertEquals(instance.rulesetId(), loaded.rulesetId());
        assertEquals(instance.lifecycle(), loaded.lifecycle());
        assertEquals(state, loaded.worldState());
        assertTrue(Files.exists(saveRoot.resolve("demo.json")));
    }

    @Test
    void returnsEmptyForMissingSave() {
        FileGameRepository repository = new FileGameRepository(new ObjectMapper(), saveRoot, new com.gameneko.aigame.infrastructure.ClasspathGameStateTypeResolver());
        assertTrue(repository.load("missing").isEmpty());
    }

    @Test
    void rejectsTraversalAndCorruptSave() throws Exception {
        FileGameRepository repository = new FileGameRepository(new ObjectMapper(), saveRoot, new com.gameneko.aigame.infrastructure.ClasspathGameStateTypeResolver());
        assertThrows(IllegalArgumentException.class, () -> repository.load("../outside"));

        Files.createDirectories(saveRoot);
        Files.writeString(saveRoot.resolve("broken.json"), "{\"schemaVersion\":99}");
        assertThrows(GamePersistenceException.class, () -> repository.load("broken"));
    }

    private static WorldState state() {
        return new WorldState("isekai_grey", "1.0", 0, "灰烬界", null,
                "白栎镇", "forest", null, 1, "清晨", null, null,
                Map.of(), "Dormant", 0, 7, java.util.List.of(), java.util.List.of(),
                java.util.List.of(), 0, false, 0, java.util.List.of(), Map.of(), "");
    }
}
