package com.gameneko.aigame.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.application.port.GameRepository;
import com.gameneko.aigame.engine.GameState;
import com.gameneko.aigame.engine.WorldState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.regex.Pattern;

/** JSON file adapter for the current in-memory world state. */
@Component
public final class FileGameRepository implements GameRepository {
    private static final int SCHEMA_VERSION = 1;
    private static final Pattern SAFE_GAME_ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");
    private final ObjectMapper objectMapper;
    private final Path saveRoot;

    @Autowired
    public FileGameRepository(ObjectMapper objectMapper,
                              @Value("${aigame.persistence.root:${user.home}/.aigame/saves}") String saveRoot) {
        this(objectMapper, Path.of(saveRoot));
    }

    public FileGameRepository(ObjectMapper objectMapper, Path saveRoot) {
        this.objectMapper = objectMapper;
        this.saveRoot = saveRoot.toAbsolutePath().normalize();
    }

    @Override
    public Optional<GameState> load(String gameId) {
        Path path = savePath(gameId);
        if (!Files.exists(path)) return Optional.empty();
        try {
            JsonNode envelope = objectMapper.readTree(Files.readString(path));
            validateEnvelope(envelope, gameId);
            return Optional.of(objectMapper.treeToValue(envelope.get("state"), WorldState.class));
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof GamePersistenceException persistenceException) throw persistenceException;
            throw new GamePersistenceException("Unable to read save: " + gameId, exception);
        }
    }

    @Override
    public void save(String gameId, GameState state) {
        if (!(state instanceof WorldState worldState)) {
            throw new GamePersistenceException("Unsupported game state type: "
                    + (state == null ? "null" : state.getClass().getName()));
        }
        Path path = savePath(gameId);
        try {
            Files.createDirectories(saveRoot);
            Path temporary = Files.createTempFile(saveRoot, "." + gameId + ".", ".tmp");
            var envelope = objectMapper.createObjectNode();
            envelope.put("schemaVersion", SCHEMA_VERSION);
            envelope.put("gameId", gameId);
            envelope.set("state", objectMapper.valueToTree(worldState));
            Files.writeString(temporary, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(envelope),
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveUnavailable) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new GamePersistenceException("Unable to write save: " + gameId, exception);
        }
    }

    private Path savePath(String gameId) {
        if (gameId == null || !SAFE_GAME_ID.matcher(gameId).matches()) {
            throw new IllegalArgumentException("gameId must contain only letters, digits, '_' or '-'");
        }
        return saveRoot.resolve(gameId + ".json").normalize();
    }

    private static void validateEnvelope(JsonNode envelope, String gameId) {
        if (envelope == null || !envelope.isObject()
                || envelope.path("schemaVersion").asInt(-1) != SCHEMA_VERSION
                || !gameId.equals(envelope.path("gameId").asText())
                || !envelope.path("state").isObject()) {
            throw new GamePersistenceException("Invalid save format: " + gameId);
        }
    }
}
