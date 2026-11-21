package com.gameneko.aigame.api;

import com.gameneko.aigame.application.NarrativeRuntimeSettingsService;
import org.springframework.web.bind.annotation.*;

/** 提供运行时叙事模型配置的读写 HTTP 接口。 */
@RestController
@RequestMapping("/api/settings/narrative")
public final class NarrativeSettingsController {
    private final NarrativeRuntimeSettingsService settings;
    /** 创建配置接口适配器。 @param settings 运行时配置服务 */
    public NarrativeSettingsController(NarrativeRuntimeSettingsService settings) { this.settings = settings; }
    /** 读取当前叙事配置。 */
    @GetMapping public NarrativeRuntimeSettingsService.Settings get() { return settings.read(); }
    /** 保存并立即持久化叙事配置。 @param request 客户端提交的配置 */
    @PutMapping public NarrativeRuntimeSettingsService.Settings put(@RequestBody NarrativeRuntimeSettingsService.Settings request) { return settings.write(request); }
}
