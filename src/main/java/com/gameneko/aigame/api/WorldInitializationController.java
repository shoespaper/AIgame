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

import java.util.HashMap;
import java.util.Map;

/** 提供直接创建世界与游戏实例的底层初始化端点（非 AI 问答），供 API 与测试使用。 */
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
     * 直接创建并持久化一个游戏实例（默认 ap 引擎与默认角色答案）。
     * @param gameId 游戏标识
     * @param request 世界与角色覆盖请求
     */
    @PostMapping("/games/{gameId}/initialize")
    public GameInstanceResponse initializeGame(@PathVariable String gameId, @RequestBody InitializeWorldRequest request) {
        if (request == null || request.worldId() == null) throw new IllegalArgumentException("worldId is required");
        var instance = instances.initialize(gameId, request, "ap", defaultAnswers(request));
        saves.save(instance);
        return GameInstanceResponse.from(instance);
    }

    /** 从请求覆盖字段构建默认角色答案，缺失时使用内置默认值。 */
    /** @param request 初始化请求 */
    private static Map<String, InitializationValue> defaultAnswers(InitializeWorldRequest request) {
        Map<String, InitializationValue> answers = new HashMap<>();
        answers.put("characterName", new InitializationValue.Text(request.characterName() == null ? "旅人" : request.characterName()));
        answers.put("age", new InitializationValue.IntegerNumber(request.age() == null ? 16 : request.age()));
        answers.put("gender", new InitializationValue.Text(request.gender() == null ? "未定" : request.gender()));
        answers.put("archetype", new InitializationValue.Text(request.archetype() == null ? "swordsman" : request.archetype()));
        answers.put("difficulty", new InitializationValue.Text(request.difficulty() == null ? "adventure" : request.difficulty()));
        return answers;
    }
}
