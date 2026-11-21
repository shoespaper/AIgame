package com.gameneko.aigame.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 管理本地单用户应用的运行时叙事模型配置。 */
@Service
public final class NarrativeRuntimeSettingsService {
    private final ObjectMapper mapper;
    private final Path path;
    /** 创建配置服务并确定配置文件位置。 */
    public NarrativeRuntimeSettingsService(ObjectMapper mapper, @Value("${aigame.narrative.settings-file:./data/narrative-settings.json}") String file) { this.mapper = mapper; this.path = Path.of(file).toAbsolutePath().normalize(); }
    /** 读取持久化的运行时配置，文件不存在时返回启动配置默认值。 */
    public Settings read() {
        try { if (Files.exists(path)) return mapper.readValue(Files.readString(path), Settings.class); }
        catch (IOException exception) { throw new IllegalStateException("Unable to read narrative settings", exception); }
        return new Settings("", false, "", "");
    }
    /** 校验并持久化运行时配置。 @param settings 用户提交的模型配置 */
    public Settings write(Settings settings) {
        if (settings == null) throw new IllegalArgumentException("settings is required");
        if (settings.enabled() && settings.apiKey().isBlank()) throw new IllegalArgumentException("API key is required when external model is enabled");
        try { Files.createDirectories(path.getParent()); Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(settings)); return settings; }
        catch (IOException exception) { throw new IllegalStateException("Unable to write narrative settings", exception); }
    }
    /** 运行时模型配置字段。 */
    public record Settings(String apiKey, boolean enabled, String baseUrl, String model) {
        public Settings { apiKey = apiKey == null ? "" : apiKey; baseUrl = baseUrl == null ? "" : baseUrl; model = model == null ? "" : model; }
    }
}
