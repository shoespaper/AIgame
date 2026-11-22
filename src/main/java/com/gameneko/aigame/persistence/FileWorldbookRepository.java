package com.gameneko.aigame.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gameneko.aigame.content.WorldbookDefinition;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/** 负责读写 data/worldbooks 下的用户世界书，并与 classpath 世界书保持独立。 */
@Component
public final class FileWorldbookRepository {
    private final ObjectMapper objectMapper;
    private final Path root;

    /** 使用默认 data/worldbooks 目录创建仓储。
     * @param objectMapper JSON 序列化器
     */
    public FileWorldbookRepository(ObjectMapper objectMapper) {
        this(objectMapper, Paths.get("data", "worldbooks"));
    }

    /** 使用配置的内容根目录创建世界书仓储。
     * @param objectMapper JSON 序列化器
     * @param contentRoot 本地内容根目录，默认 data
     */
    @Autowired
    public FileWorldbookRepository(ObjectMapper objectMapper,
                                   @org.springframework.beans.factory.annotation.Value("${aigame.content.root:./data}") String contentRoot) {
        this(objectMapper, Path.of(contentRoot).resolve("worldbooks"));
    }

    /** 使用指定目录创建仓储，便于测试隔离文件系统。
     * @param objectMapper JSON 序列化器
     * @param root 世界书存储目录
     */
    public FileWorldbookRepository(ObjectMapper objectMapper, Path root) {
        this.objectMapper = objectMapper;
        this.root = root.toAbsolutePath().normalize();
    }

    /** 返回本地目录中的用户世界书标识。
     * @return 排序后的世界书标识
     */
    public List<String> available() {
        if (!Files.isDirectory(root)) return List.of();
        try (var paths = Files.list(root)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> path.getFileName().toString().replaceFirst("\\.json$", ""))
                    .sorted().toList();
        } catch (IOException exception) {
            throw new GamePersistenceException("Unable to list worldbooks", exception);
        }
    }

    /** 读取用户世界书原始 JSON。
     * @param id 世界书标识
     * @return JSON 文档
     */
    public JsonNode readDocument(String id) {
        Path file = safePath(id);
        if (!Files.isRegularFile(file)) return null;
        try {
            return objectMapper.readTree(Files.readString(file));
        } catch (IOException exception) {
            throw new GamePersistenceException("Unable to read worldbook: " + id, exception);
        }
    }

    /** 保存一个世界书实例，默认拒绝覆盖已有文件。
     * @param worldbook 世界书叙事内容
     */
    public synchronized void save(WorldbookDefinition worldbook) {
        save(worldbook, false);
    }

    /** 保存一个世界书实例，可选覆盖已有文件。
     * @param worldbook 世界书叙事内容
     * @param overwrite 是否覆盖已存在的同名世界书
     */
    public synchronized void save(WorldbookDefinition worldbook, boolean overwrite) {
        if (worldbook == null) throw new IllegalArgumentException("Worldbook is required");
        Path file = safePath(worldbook.id());
        if (!overwrite && Files.exists(file)) throw new IllegalArgumentException("Worldbook already exists: " + worldbook.id());
        Path temporary = null;
        try {
            Files.createDirectories(root);
            ObjectNode document = objectMapper.createObjectNode();
            document.set("_meta", objectMapper.valueToTree(Map.of(
                    "version", worldbook.contentVersion(), "worldbook_id", worldbook.id(),
                    "world_name", worldbook.id(), "template_id", worldbook.templateId(), "type", worldbook.type())));
            ObjectNode setting = objectMapper.createObjectNode();
            setting.put("world_name_hint", worldbook.name());
            setting.put("world_desc_hint", worldbook.descriptionHint());
            setting.put("template_id", worldbook.templateId());
            setting.put("type", worldbook.type());
            setting.put("tone", worldbook.tone());
            setting.set("factions", objectMapper.valueToTree(worldbook.factions()));
            setting.set("origin_contexts", objectMapper.valueToTree(worldbook.originContexts()));
            setting.set("world_tropes", objectMapper.valueToTree(worldbook.worldTropes()));
            setting.set("adventurer_guild", objectMapper.valueToTree(worldbook.adventurerGuild()));
            document.set("world_setting", setting);
            temporary = Files.createTempFile(root, ".worldbook-", ".tmp");
            Files.writeString(temporary, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(document));
            try {
                Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file);
            }
        } catch (IOException exception) {
            throw new GamePersistenceException("Unable to save worldbook: " + worldbook.id(), exception);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* 不覆盖原始写入异常。 */ }
            }
        }
    }

    /** 删除一个用户世界书文件。
     * @param id 世界书标识
     */
    public synchronized void delete(String id) {
        Path file = safePath(id);
        try {
            if (!Files.deleteIfExists(file)) throw new IllegalArgumentException("Worldbook not found: " + id);
        } catch (IOException exception) {
            throw new GamePersistenceException("Unable to delete worldbook: " + id, exception);
        }
    }

    /** 将标识映射到仓储根目录内的安全路径。
     * @param id 世界书标识
     * @return JSON 文件路径
     */
    private Path safePath(String id) {
        if (id == null || id.isBlank() || !id.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid worldbook id");
        }
        Path file = root.resolve(id + ".json").normalize();
        if (!file.startsWith(root)) throw new IllegalArgumentException("Invalid worldbook path");
        return file;
    }
}
