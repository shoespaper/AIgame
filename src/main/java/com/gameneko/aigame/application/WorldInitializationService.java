package com.gameneko.aigame.application;

import com.gameneko.aigame.content.ContentCatalog;
import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.engine.WorldState;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 创建世界动态状态，允许没有具体机制模板的纯叙事世界。 */
@Service
public final class WorldInitializationService {
    private final ContentCatalog contentCatalog;

    /** 创建世界初始化服务。
     * @param contentCatalog 世界书与模板组合目录
     */
    public WorldInitializationService(ContentCatalog contentCatalog) {
        this.contentCatalog = contentCatalog;
    }

    /** 使用默认开场初始化世界。
     * @param worldId 世界书标识
     * @return 世界初始状态
     */
    public WorldState initialize(String worldId) {
        return initialize(InitializeWorldRequest.defaults(worldId));
    }

    /** 使用指定开场描述初始化世界。
     * @param worldId 世界书稳定标识
     * @param originScene 开场场景描述，可空
     * @return 世界初始状态
     */
    public WorldState initialize(String worldId, String originScene) {
        return initialize(new InitializeWorldRequest(worldId, null, null, null, null,
                null, null, originScene));
    }

    /** 将已确认请求转换为初始世界状态。
     * @param request 世界书选择和开场覆盖值
     * @return 世界初始状态
     */
    public WorldState initialize(InitializeWorldRequest request) {
        Objects.requireNonNull(request, "request");
        WorldDefinition definition = contentCatalog.world(request.worldId());
        return new WorldState(definition.worldId(), definition.contentVersion(), 0,
                request.name(), request.description(), request.location(), request.region(), request.mainQuest(),
                1, request.time() == null ? "清晨" : request.time(), Map.of(),
                "Dormant", 0, definition.defaultThreatInterval(), List.of(), List.of(), List.of(),
                0, false, 0, List.of(), Map.of(), request.originScene() == null ? "" : request.originScene(),
                request.location() == null || request.location().isBlank() ? List.of() : List.of(request.location()));
    }
}
