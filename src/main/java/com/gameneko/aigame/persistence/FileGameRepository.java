package com.gameneko.aigame.persistence;

import com.gameneko.aigame.application.port.GamePersistenceException;
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
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.regex.Pattern;

/** 将当前游戏状态读写到本地 JSON 存档。 */
@Component
public final class FileGameRepository implements GameRepository {
    private static final int SCHEMA_VERSION = 3;
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
        return read(gameId, false);
    }

    /** 读取当前格式的存档，浏览目录时可略过已停用的格式。
     * @param gameId 游戏存档标识
     * @param skipObsolete 浏览目录时为 true，直接加载时为 false
     */
    private Optional<GameInstance<?>> read(String gameId, boolean skipObsolete) {
        Path path = savePath(gameId);
        String document;
        try {
            document = Files.readString(path);
        } catch (NoSuchFileException exception) {
            return Optional.empty();
        } catch (IOException exception) {
            throw new GamePersistenceException("Unable to read save: " + gameId, exception,
                    "game_save", gameId, "read", "read_file");
        }
        try {
            JsonNode envelope = objectMapper.readTree(document);
            if (skipObsolete && envelope != null) {
                JsonNode version = envelope.path("schemaVersion");
                if (version.isIntegralNumber() && version.canConvertToInt()
                        && version.intValue() > 0 && version.intValue() < SCHEMA_VERSION) {
                    return Optional.empty();
                }
            }
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
            throw new GamePersistenceException(message, exception, "game_save", gameId, "read", "parse_json");
        }
    }

    /** 先写入临时文件，再替换指定游戏存档。
     * @param instance 待持久化的权威游戏实例
     */
    @Override
    public synchronized void save(GameInstance<?> instance) {
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

    /** 按已校验的游戏标识删除对应文件，支持对同一标识安全重试。
     * @param gameId 游戏存档标识
     */
    @Override
    public synchronized void delete(String gameId) {
        Path path = savePath(gameId);
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new GamePersistenceException("Unable to delete save: " + gameId, exception,
                    "game_save", gameId, "delete", "delete_file");
        }
    }

    /** 列出当前格式的存档和最后写入时间，按保存时间从新到旧排列。 */
    @Override
    public synchronized List<SavedGame> list() {
        if (!Files.isDirectory(saveRoot)) return List.of();
        try (Stream<Path> files = Files.list(saveRoot)) {
            List<SavedGame> saves = new ArrayList<>();
            for (Path path : files.filter(file -> file.getFileName().toString().endsWith(".json")).toList()) {
                String fileName = path.getFileName().toString();
                Optional<GameInstance<?>> instance = read(fileName.substring(0, fileName.length() - ".json".length()), true);
                if (instance.isEmpty()) continue;
                try {
                    saves.add(new SavedGame(instance.get(), Files.getLastModifiedTime(path).toInstant()));
                } catch (NoSuchFileException exception) {
                    // 存档可能在读取后被外部进程删除。
                }
            }
            return saves.stream()
                    .sorted(Comparator.comparing(SavedGame::updatedAt, Comparator.reverseOrder())
                            .thenComparing(save -> save.instance().gameId()))
                    .toList();
        } catch (IOException exception) {
            throw new GamePersistenceException("Unable to list saves", exception, "game_save", null, "list", "list_directory");
        }
    }

    /** 校验标识并解析其对应的存档路径。
     * @param gameId 由字母、数字、下划线或连字符组成的游戏标识
     */
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
