package com.gameneko.aigame.api;

import com.gameneko.aigame.content.WorldBookCatalog;
import com.gameneko.aigame.engine.GameEngineCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供选世界书和引擎所需的静态目录，不暴露旧的表单 schema 接口。 */
@RestController
@RequestMapping("/api/games/initialization")
public final class GameCatalogController {
    private final WorldBookCatalog worlds;
    private final GameEngineCatalog engines;

    /** 创建目录控制器。
     * @param worlds 已注册世界书目录
     * @param engines 已注册可选引擎目录
     */
    public GameCatalogController(WorldBookCatalog worlds, GameEngineCatalog engines) {
        this.worlds = worlds;
        this.engines = engines;
    }

    /** 返回世界书和可选引擎的独立目录。
     * @return 新游戏选择所需的目录响应
     */
    @GetMapping("/catalog")
    public CatalogResponse catalog() {
        return new CatalogResponse(worlds.available().stream().map(book ->
                new WorldChoice(book.id(), book.version(), book.definition().nameHint(), book.definition().descriptionHint())).toList(),
                engines.available());
    }

    /** 目录响应。
     * @param worlds 可选世界书
     * @param engines 可选引擎
     */
    public record CatalogResponse(java.util.List<WorldChoice> worlds,
                                  java.util.List<com.gameneko.aigame.engine.GameEngineDescriptor> engines) {}

    /** 世界书的展示资料。
     * @param id 世界书标识
     * @param version 内容版本
     * @param name 展示名称
     * @param description 展示描述
     */
    public record WorldChoice(String id, String version, String name, String description) {}
}
