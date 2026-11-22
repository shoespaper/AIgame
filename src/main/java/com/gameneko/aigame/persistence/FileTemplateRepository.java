package com.gameneko.aigame.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.content.WorldTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.IOException;

/** 负责读写 data/templates 下的用户模板。 */
@Component
public final class FileTemplateRepository {
    private final ObjectMapper objectMapper;
    private final Path root;
    /** 创建模板仓储。
     * @param objectMapper JSON 序列化器
     */
    public FileTemplateRepository(ObjectMapper objectMapper) {
        this(objectMapper, Paths.get("data", "templates"));
    }

    /** 使用配置的内容根目录创建模板仓储。
     * @param objectMapper JSON 序列化器
     * @param contentRoot 本地内容根目录，默认 data
     */
    @Autowired
    public FileTemplateRepository(ObjectMapper objectMapper,
                                  @org.springframework.beans.factory.annotation.Value("${aigame.content.root:./data}") String contentRoot) {
        this(objectMapper, Path.of(contentRoot).resolve("templates"));
    }

    /** 使用指定目录创建模板仓储。
     * @param objectMapper JSON 序列化器
     * @param root 模板存储目录
     */
    public FileTemplateRepository(ObjectMapper objectMapper, Path root) {
        this.objectMapper = objectMapper;
        this.root = root.toAbsolutePath().normalize();
    }

    /** 返回用户模板目录中的标识。
     * @return 排序后的用户模板标识列表
     */
    public List<String> available() {
        if (!Files.isDirectory(root)) return List.of();
        try (var paths = Files.list(root)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> path.getFileName().toString().replaceFirst("\\.json$", ""))
                    .sorted().toList();
        } catch (IOException exception) {
            throw new GamePersistenceException("Unable to list templates", exception);
        }
    }

    /** 原子写入完整用户模板，创建时拒绝覆盖同名文件。
     * @param template 包含机制骨架和创建默认值的模板
     */
    public synchronized void save(WorldTemplate template) {
        if (template == null || !template.id().matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid template id");
        Path destination = root.resolve(template.id() + ".json");
        if (Files.exists(destination)) throw new IllegalArgumentException("Template already exists: " + template.id());
        Path temporary = null;
        try {
            Files.createDirectories(root);
            temporary = Files.createTempFile(root, ".template-", ".tmp");
            Files.writeString(temporary, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(template));
            try {
                Files.move(temporary, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination);
            }
        } catch (IOException exception) {
            throw new GamePersistenceException("Unable to save template: " + template.id(), exception);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* 不覆盖原始写入异常。 */ }
            }
        }
    }

    /** 读取用户模板的原始文档。
     * @param templateId 模板标识
     * @return JSON 文档，不存在时返回 null
     */
    public JsonNode readDocument(String templateId) {
        if (templateId == null || !templateId.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid template id");
        Path file = root.resolve(templateId + ".json").normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) return null;
        try {
            return objectMapper.readTree(Files.readString(file));
        } catch (IOException exception) {
            throw new GamePersistenceException("Unable to read template: " + templateId, exception);
        }
    }
}
