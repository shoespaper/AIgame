package com.gameneko.aigame.infrastructure;

import com.gameneko.aigame.application.GameEngineFactory;
import com.gameneko.aigame.application.GameRequestException;
import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.engine.GameEngineRuntime;
import com.gameneko.aigame.rulesets.ap.ApGameEngineRuntime;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.gameneko.aigame.persistence.ConfirmedTemplateRegistry;
import com.gameneko.aigame.rulesets.ap.ApTemplateContent;

/** Runtime registry for the engines currently shipped by the application. */
@Component
public final class ClasspathGameEngineFactory implements GameEngineFactory {
    private ConfirmedTemplateRegistry registry;

    /** 注入确认阶段登记的引擎模板目录。
     * @param templates 已注册模板
     */
    @Autowired
    void configureTemplates(ConfirmedTemplateRegistry templates) { registry = templates; }

    @Override
    public GameEngineRuntime<?, ?> create(String engineId, WorldDefinition world) {
        return createForGame(engineId, world, null);
    }

    /** 读取当前游戏确认的素材后创建所选引擎。
     * @param engineId 玩家选择的引擎标识
     * @param world 世界书基础内容
     * @param gameId 已确认的游戏标识，角色初始化访谈期间为空
     */
    @Override
    public GameEngineRuntime<?, ?> createForGame(String engineId, WorldDefinition world, String gameId) {
        if (engineId == null || engineId.isBlank()) throw new GameRequestException(GameRequestException.Code.ENGINE_REQUIRED, "Game engine is not selected");
        return switch (engineId.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "ap" -> registry == null || gameId == null ? new ApGameEngineRuntime() : ApTemplateContent.runtime(registry.world(gameId), registry.rules("ap"));
            default -> throw new GameRequestException(GameRequestException.Code.UNSUPPORTED_ENGINE, "Unsupported game engine: " + engineId);
        };
    }
}
