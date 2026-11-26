package com.gameneko.aigame.persistence;

import com.gameneko.aigame.application.port.GamePersistenceException;
import com.gameneko.aigame.application.port.TemplateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.content.WorldTemplate;
import com.gameneko.aigame.application.GameRequestException;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.io.IOException;

/** 负责读写 data/templates 下的用户模板。 */
@Component
public final class FileTemplateRepository implements TemplateRepository {
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
            throw new GamePersistenceException("Unable to list templates", exception, "template", null, "list", "list_directory");
        }
    }

    /** 原子写入完整用户模板，创建时拒绝覆盖同名文件。
     * @param template 包含机制骨架和创建默认值的模板
     */
    @Override
    public synchronized void save(WorldTemplate template) {
        if (template == null || !template.id().matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid template id");
        Path destination = root.resolve(template.id() + ".json");
        if (Files.exists(destination)) throw new GameRequestException(GameRequestException.Code.CONTENT_ALREADY_EXISTS, "Template already exists: " + template.id());
        Path temporary = null;
        String stage = "create_directory";
        try {
            Files.createDirectories(root);
            stage = "encode_json";
            String document = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(template);
            stage = "write_temp";
            temporary = Files.createTempFile(root, ".template-", ".tmp");
            Files.writeString(temporary, document);
            stage = "replace_file";
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination);
            }
        } catch (IOException | IllegalArgumentException exception) {
            TemporaryFiles.deleteAfterFailure(temporary, exception);
            throw new GamePersistenceException("Unable to save template: " + template.id(), exception,
                    "template", template.id(), "write", stage);
        }
    }

    /** 读取用户模板的原始文档。
     * @param templateId 模板标识
     * @return JSON 文档，不存在时返回 null
     */
    public JsonNode readDocument(String templateId) {
        if (templateId == null || !templateId.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid template id");
        Path file = root.resolve(templateId + ".json").normalize();
        if (!file.startsWith(root) || Files.notExists(file)) return null;
        String stage = "read_file";
        try {
            String document = Files.readString(file);
            stage = "parse_json";
            return objectMapper.readTree(document);
        } catch (IOException exception) {
            throw new GamePersistenceException("Unable to read template: " + templateId, exception,
                    "template", templateId, "read", stage);
        }
    }
}
