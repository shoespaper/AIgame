package com.gameneko.aigame.api;

import com.gameneko.aigame.application.GameInstanceService;
import com.gameneko.aigame.application.GameSaveService;
import com.gameneko.aigame.application.InitializeWorldRequest;
import com.gameneko.aigame.application.WorldInitializationService;
import com.gameneko.aigame.engine.InitializationValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
/** 提供直接创建世界与游戏实例的初始化端点，使用调用方显式选择的可选引擎。 */
@RestController
@RequestMapping("/api")
public final class WorldInitializationController {
    private final WorldInitializationService worlds;
    private final GameInstanceService instances;
    private final GameSaveService saves;

    /**
     * 创建初始化控制器。
     * @param worlds 世界初始化服务
     * @param instances 游戏实例创建服务
     * @param saves 持久化新建游戏实例的服务
     */
    public WorldInitializationController(WorldInitializationService worlds, GameInstanceService instances, GameSaveService saves) {
        this.worlds = worlds;
        this.instances = instances;
        this.saves = saves;
    }

    /**
     * 初始化一个世界并返回只读世界状态。
     * @param request 世界初始化请求
     */
    @PostMapping("/worlds/initialize")
    public WorldInitializationResponse initializeWorld(@RequestBody InitializeWorldRequest request) {
        if (request == null || request.worldId() == null) throw new IllegalArgumentException("worldId is required");
        return WorldInitializationResponse.from(worlds.initialize(request));
    }

    /**
     * 直接创建并持久化一个游戏实例，并应用所选引擎模块提供的默认字段。
     * @param gameId 游戏标识
     * @param request 世界书选择、可选引擎及类型化字段覆盖值
     */
    @PostMapping("/games/{gameId}/initialize")
    public GameInstanceResponse initializeGame(@PathVariable String gameId, @RequestBody GameInitializationRequest request) {
        if (request == null || request.worldId() == null) throw new IllegalArgumentException("worldId is required");
        var world = request.worldRequest();
        var instance = instances.initializeWithDefaults(gameId, world, request.engineId(),
                request.answers() == null ? Map.of() : request.answers());
        saves.save(instance);
        return GameInstanceResponse.from(instance);
    }



    /** 兼容直接建档入口的选择、共享世界覆盖和类型化字段请求。
     * @param worldId 世界书稳定标识
     * @param engineId 可空的可选规则引擎标识
     * @param name 可空的世界名称覆盖值
     * @param description 可空的世界描述覆盖值
     * @param location 可空的开场地点文本
     * @param region 可空的区域稳定标识
     * @param time 可空的开场时间
     * @param mainQuest 可空的开场主线方向
     * @param originScene 可空的开场场景描述
     * @param answers 调用方提供的类型化字段覆盖值
     */
    public record GameInitializationRequest(String worldId, String engineId, String name, String description,
                                            String location, String region, String time,
                                            String mainQuest, String originScene,
                                            Map<String, InitializationValue> answers) {
        /** 转换为只含世界书共享字段的初始化请求。
         * @return 世界书共享字段请求
         */
        public InitializeWorldRequest worldRequest() {
            return new InitializeWorldRequest(worldId, name, description, location, region,
                    time, mainQuest, originScene);
        }
    }
}
