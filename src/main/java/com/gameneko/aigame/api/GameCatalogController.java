package com.gameneko.aigame.api;

import com.gameneko.aigame.application.GameRequestException;

import com.gameneko.aigame.content.WorldBookCatalog;
import com.gameneko.aigame.content.TemplateCatalog;
import com.gameneko.aigame.content.WorldTemplate;
import com.gameneko.aigame.content.WorldbookDefinition;
import com.gameneko.aigame.engine.GameEngineCatalog;
import com.gameneko.aigame.engine.GameEngineDescriptor;
import com.gameneko.aigame.application.port.WorldbookRepository;
import com.gameneko.aigame.application.port.TemplateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 提供选世界书和引擎所需的静态目录，不暴露旧的表单 schema 接口。 */
@RestController
@RequestMapping("/api/games/initialization")
public final class GameCatalogController {
    private final WorldBookCatalog worlds;
    private final TemplateCatalog templates;
    private final GameEngineCatalog engines;
    private final WorldbookRepository worldbookRepository;
    private final TemplateRepository templateRepository;

    /** 创建目录控制器。
     * @param worlds 已注册世界书目录
     * @param templates 内置与用户模板目录
     * @param engines 已注册可选引擎目录
     * @param worldbookRepository 用户世界书仓储
     * @param templateRepository 用户模板仓储
     */
    @Autowired
    public GameCatalogController(WorldBookCatalog worlds, TemplateCatalog templates,
                                 GameEngineCatalog engines, WorldbookRepository worldbookRepository,
                                 TemplateRepository templateRepository) {
        this.worlds = worlds;
        this.templates = templates;
        this.engines = engines;
        this.worldbookRepository = worldbookRepository;
        this.templateRepository = templateRepository;
    }

    /** 返回世界书和可选引擎的独立目录。
     * @return 新游戏选择所需的目录响应
     */
    @GetMapping("/catalog")
    public CatalogResponse catalog() {
        return new CatalogResponse(worlds.available().stream().map(book ->
                new WorldChoice(book.id(), book.version(), book.definition().nameHint(), book.definition().descriptionHint(),
                        book.type(), book.templateId(), isEditable(book.id()))).toList(),
                engines.available());
    }

    /** 返回单个世界书的完整叙事内容供编辑。
     * @param id 世界书标识
     * @return 世界书叙事内容
     */
    @GetMapping("/worldbooks/{id}")
    public WorldbookDefinition worldbook(@PathVariable String id) {
        return worlds.worldBook(id).narrative();
    }

    /** 覆盖保存一个自定义世界书。
     * @param id 世界书标识
     * @param request 更新后的世界书叙事内容
     * @return 世界书标识
     */
    @PutMapping("/worldbooks/{id}")
    public WorldbookResponse updateWorldbook(@PathVariable String id, @RequestBody WorldbookRequest request) {
        if (request == null) throw GameRequestException.invalid("Worldbook is required");
        requireEditable(id);
        validateConfiguration(request.type(), request.name(), request.tone());
        if (request.templateId() != null && !request.templateId().isBlank()
                && !templates.template(request.templateId()).type().equals(request.type())) {
            throw GameRequestException.invalid("Template type mismatch");
        }
        WorldbookDefinition worldbook = new WorldbookDefinition(id, "1.0", request.name(), request.type(), request.templateId(),
                request.tone(), request.descriptionHint(), request.factions(), request.originContexts(),
                request.worldTropes(), request.adventurerGuild());
        worldbookRepository.save(worldbook, true);
        worlds.invalidate(id);
        return new WorldbookResponse(id);
    }

    /** 删除一个自定义世界书。
     * @param id 世界书标识
     */
    @DeleteMapping("/worldbooks/{id}")
    public void deleteWorldbook(@PathVariable String id) {
        requireEditable(id);
        worldbookRepository.delete(id);
        worlds.invalidate(id);
    }

    /** 校验世界书是否为可编辑的自定义内容，内置内容拒绝修改。
     * @param id 世界书标识
     */
    private void requireEditable(String id) {
        if (!isEditable(id)) throw new GameRequestException(GameRequestException.Code.CONTENT_READ_ONLY, "Built-in worldbook is read-only: " + id);
    }

    /** 判断世界书是否为用户自定义（可编辑删除）。
     * @param id 世界书标识
     * @return 自定义内容返回真
     */
    private boolean isEditable(String id) {
        return worldbookRepository.available().contains(id);
    }

    /** 保存自定义世界书实例并返回可用于初始化的世界书标识。
     * @param request 世界书叙事内容和模板引用
     * @return 新世界书标识
     */
    @PostMapping("/worldbooks")
    public WorldbookResponse saveWorldbook(@RequestBody WorldbookRequest request) {
        if (request == null) throw GameRequestException.invalid("Worldbook is required");
        validateConfiguration(request.type(), request.name(), request.tone());
        String id = newId("custom_", request.id());
        if (worlds.available().stream().anyMatch(book -> book.id().equals(id)) || "isekai_grey".equals(id)) {
            throw new GameRequestException(GameRequestException.Code.CONTENT_ALREADY_EXISTS, "Worldbook already exists: " + id);
        }
        if (request.templateId() != null && !request.templateId().isBlank()
                && !templates.template(request.templateId()).type().equals(request.type())) {
            throw GameRequestException.invalid("Template type mismatch");
        }
        WorldbookDefinition worldbook = new WorldbookDefinition(id, "1.0", request.name(), request.type(), request.templateId(),
                request.tone(), request.descriptionHint(), request.factions(), request.originContexts(),
                request.worldTropes(), request.adventurerGuild());
        worldbookRepository.save(worldbook);
        return new WorldbookResponse(id);
    }

    /** 保存可复用模板元数据，并复用一个已有模板的机制骨架。
     * @param request 模板标识、类型、标签和基础模板
     * @return 新模板标识
     */
    @PostMapping("/templates")
    public TemplateResponse saveTemplate(@RequestBody TemplateRequest request) {
        if (request == null) throw GameRequestException.invalid("Template is required");
        validateIdentity(request.type(), request.name());
        WorldTemplate base = request.baseTemplateId() == null || request.baseTemplateId().isBlank()
                ? WorldTemplate.empty(request.type(), request.name()) : templates.template(request.baseTemplateId());
        if (!base.type().equals(request.type())) throw GameRequestException.invalid("Template type mismatch");
        String id = newId("template_", request.id());
        if (templates.availableTemplates().stream().anyMatch(template -> template.id().equals(id))) {
            throw new GameRequestException(GameRequestException.Code.CONTENT_ALREADY_EXISTS, "Template already exists: " + id);
        }
        templateRepository.save(base.copyAs(id, request.type(), request.name().trim(), request.tags()));
        return new TemplateResponse(id);
    }

    /** 校验世界书的类型、名称与基调。
     * @param type 世界类型稳定标识
     * @param name 世界书展示名
     * @param tone 故事基调稳定标识
     */
    private void validateConfiguration(String type, String name, String tone) {
        validateIdentity(type, name);
        if (tone == null || !List.of("light", "balanced", "hardcore").contains(tone)) {
            throw GameRequestException.invalid("Invalid story tone");
        }
    }

    /** 校验世界书和模板共用的类型标识和展示名。
     * @param type 世界类型稳定标识
     * @param name 最长 200 字符的展示名
     */
    private void validateIdentity(String type, String name) {
        if (!WorldbookDefinition.isValidTypeId(type)) {
            throw GameRequestException.invalid("世界类型须以小写字母开头，使用小写字母、数字、下划线或连字符，共 2 至 64 个字符");
        }
        if (name == null || name.isBlank() || name.length() > 200) throw GameRequestException.invalid("World name is required (maximum 200 characters)");
    }

    /** 生成或校验新内容的安全标识。
     * @param prefix 自动生成标识时的类型前缀
     * @param supplied 可空的用户指定标识
     * @return 安全标识
     */
    private String newId(String prefix, String supplied) {
        String id = supplied == null || supplied.isBlank()
                ? prefix + java.util.UUID.randomUUID().toString().replace("-", "") : supplied.trim();
        if (!id.matches("[A-Za-z0-9_-]{1,100}")) throw GameRequestException.invalid("Invalid content id");
        return id;
    }

    /** 目录响应。
     * @param worlds 可选世界书
     * @param engines 可选引擎
     */
    public record CatalogResponse(List<WorldChoice> worlds, List<GameEngineDescriptor> engines) {}

    /** 世界书的展示资料。
     * @param id 世界书标识
     * @param version 内容版本
     * @param name 展示名称
     * @param description 展示描述
     * @param type 世界类型稳定标识
     * @param templateId 引用模板标识，无模板时为空字符串
     * @param editable 是否为可编辑删除的自定义世界书
     */
    public record WorldChoice(String id, String version, String name, String description,
                              String type, String templateId, boolean editable) {}

    /** 自定义世界书提交数据。
     * @param id 可选稳定标识
     * @param name 世界展示名称
     * @param type 世界类型
     * @param templateId 引用模板标识
     * @param tone 故事基调
     * @param descriptionHint 世界背景
     * @param factions 势力列表
     * @param originContexts 出身列表
     * @param worldTropes 世界规则
     * @param adventurerGuild 公会叙事资料
     */
    public record WorldbookRequest(String id, String name, String type, String templateId, String tone,
                                   String descriptionHint, java.util.List<java.util.Map<String, String>> factions,
                                   java.util.List<String> originContexts, java.util.List<String> worldTropes,
                                   java.util.Map<String, Object> adventurerGuild) {}

    /** 世界书保存响应。
     * @param worldbookId 可用于后续初始化的世界书标识
     */
    public record WorldbookResponse(String worldbookId) {}

    /** 模板保存请求。
     * @param id 可选新模板标识
     * @param name 模板展示名称
     * @param type 世界类型
     * @param tags 模板标签
     * @param baseTemplateId 可空的来源模板标识
     */
    public record TemplateRequest(String id, String name, String type, List<String> tags, String baseTemplateId) {}

    /** 模板保存响应。
     * @param templateId 新模板标识
     */
    public record TemplateResponse(String templateId) {}
}
