package com.gameneko.aigame.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gameneko.aigame.application.port.GameRepository;
import com.gameneko.aigame.engine.GameInstance;
import com.gameneko.aigame.engine.WorldState;
import com.gameneko.aigame.engine.GameStateTypeResolver;
import com.gameneko.aigame.application.GameRequestException;
import com.gameneko.aigame.logging.DiagnosticLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.List;
import java.util.stream.Stream;
import java.util.regex.Pattern;

/** 将当前游戏状态读写到本地 JSON 存档。 */
@Component
public final class FileGameRepository implements GameRepository {
    private static final int SCHEMA_VERSION = 2;
    private static final Pattern SAFE_GAME_ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");
    private final ObjectMapper objectMapper;
    private final Path saveRoot;
    private final GameStateTypeResolver stateTypes;

    @Autowired
    public FileGameRepository(ObjectMapper objectMapper,
                              @Value("${aigame.persistence.root:${user.home}/.aigame/saves}") String saveRoot,
                              GameStateTypeResolver stateTypes) {
        this(objectMapper, Path.of(saveRoot), stateTypes);
    }

    public FileGameRepository(ObjectMapper objectMapper, Path saveRoot, GameStateTypeResolver stateTypes) {
        this.objectMapper = objectMapper;
        this.saveRoot = saveRoot.toAbsolutePath().normalize();
        this.stateTypes = stateTypes;
    }

    /** 读取指定游戏的存档，并区分文件读取与解析失败。
     * @param gameId 游戏存档标识
     */
    @Override
    public Optional<GameInstance<?>> load(String gameId) {
        Path path = savePath(gameId);
        if (Files.notExists(path)) return Optional.empty();
        String stage = "read_file";
        try {
            String document = Files.readString(path);
            stage = "parse_json";
            JsonNode envelope = objectMapper.readTree(document);
            validateEnvelope(envelope, gameId);
            JsonNode state = envelope.get("state");
            String rulesetId = state.path("rulesetId").asText("");
            if (rulesetId.isBlank()) {
                return Optional.of(objectMapper.treeToValue(state,
                        objectMapper.getTypeFactory().constructParametricType(GameInstance.class, WorldState.class)));
            }
            Class<?> rulesetStateType = stateTypes.stateType(rulesetId);
            return Optional.of(objectMapper.treeToValue(state,
                    objectMapper.getTypeFactory().constructParametricType(GameInstance.class, rulesetStateType)));
        } catch (IOException | IllegalArgumentException | GamePersistenceException exception) {
            String message = exception instanceof GamePersistenceException
                    ? exception.getMessage() : "Unable to read save: " + gameId;
            throw new GamePersistenceException(message, exception, "game_save", gameId, "read", stage);
        }
    }

    /** 先写入临时文件，再替换指定游戏存档。
     * @param instance 待持久化的权威游戏实例
     */
    @Override
    public void save(GameInstance<?> instance) {
        if (instance == null) throw new IllegalArgumentException("game instance must not be null");
        Path path = savePath(instance.gameId());
        Path temporary = null;
        String stage = "create_directory";
        try {
            Files.createDirectories(saveRoot);
            stage = "encode_json";
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("schemaVersion", SCHEMA_VERSION);
            envelope.put("gameId", instance.gameId());
            envelope.put("worldId", instance.worldId());
            envelope.put("worldVersion", instance.contentVersion());
            if (instance.rulesetId() != null) {
                envelope.put("engineId", instance.rulesetId());
                envelope.put("engineVersion", instance.rulesetVersion());
            } else {
                envelope.putNull("engineId");
                envelope.putNull("engineVersion");
            }
            envelope.set("state", objectMapper.valueToTree(instance));
            String document = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(envelope);
            stage = "write_temp";
            temporary = Files.createTempFile(saveRoot, "." + instance.gameId() + ".", ".tmp");
            Files.writeString(temporary, document, StandardOpenOption.TRUNCATE_EXISTING);
            stage = "replace_file";
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveUnavailable) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | IllegalArgumentException exception) {
            TemporaryFiles.deleteAfterFailure(temporary, exception);
            throw new GamePersistenceException("Unable to write save: " + instance.gameId(), exception,
                    "game_save", instance.gameId(), "write", stage);
        }
        DiagnosticLog.saveCommitted(instance.gameId(), instance.worldId(), instance.rulesetId(), instance.revision());
    }

    /** 读取保存目录中的全部有效游戏实例，供存档列表接口使用。 */
    @Override
    public List<GameInstance<?>> list() {
        if (!Files.isDirectory(saveRoot)) return List.of();
        try (Stream<Path> files = Files.list(saveRoot)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> path.getFileName().toString().replaceFirst("\\.json$", ""))
                    .map(this::load)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException exception) {
            throw new GamePersistenceException("Unable to list saves", exception, "game_save", null, "list", "list_directory");
        }
    }

    private Path savePath(String gameId) {
        if (gameId == null || !SAFE_GAME_ID.matcher(gameId).matches()) {
            throw GameRequestException.invalid("gameId must contain only letters, digits, '_' or '-'");
        }
        return saveRoot.resolve(gameId + ".json").normalize();
    }

    /**
     * 校验存档信封的格式与身份一致性。
     *
     * @param envelope JSON 存档信封，须为含 schemaVersion、gameId、state 的对象
     * @param gameId   请求加载的游戏标识，须与信封中的 gameId 一致
     */
    private static void validateEnvelope(JsonNode envelope, String gameId) {
        int version = envelope == null ? -1 : envelope.path("schemaVersion").asInt(-1);
        if (envelope == null || !envelope.isObject() || version != SCHEMA_VERSION
                || !gameId.equals(envelope.path("gameId").asText()) || !envelope.path("state").isObject()) {
            throw new GamePersistenceException("Invalid save format: " + gameId);
        }
        JsonNode state = envelope.path("state");
        if (!envelope.path("worldId").asText().equals(state.path("worldId").asText())
                || !envelope.path("worldVersion").asText().equals(state.path("contentVersion").asText())) {
            throw new GamePersistenceException("Save identity does not match state: " + gameId);
        }
        String engine = envelope.path("engineId").isNull() ? null : envelope.path("engineId").asText(null);
        String stateEngine = state.path("rulesetId").isNull() ? null : state.path("rulesetId").asText(null);
        if (!java.util.Objects.equals(engine, stateEngine)) throw new GamePersistenceException("Save engine identity does not match state: " + gameId);
        String engineVersion = envelope.path("engineVersion").isNull() ? null : envelope.path("engineVersion").asText(null);
        String stateVersion = state.path("rulesetVersion").isNull() ? null : state.path("rulesetVersion").asText(null);
        if (!java.util.Objects.equals(engineVersion, stateVersion)) throw new GamePersistenceException("Save engine version does not match state: " + gameId);
    }
}
