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
    /** 未配置时使用的输出 token 上限，足够容纳一回合完整结构化叙事。 */
    public static final int DEFAULT_MAX_TOKENS = 2048;
    /** 允许配置的输出 token 上限，避免超出模型能力的取值被服务端直接拒绝。 */
    public static final int MAX_ALLOWED_MAX_TOKENS = 65536;
    /** 允许配置的输出 token 下限，过小的取值必然导致结构化 JSON 被截断。 */
    public static final int MIN_ALLOWED_MAX_TOKENS = 256;
    private final ObjectMapper mapper;
    private final Path path;
    /**
     * 创建配置服务并确定配置文件位置。
     * @param mapper 用于读写配置文件 JSON 的映射器
     * @param file 配置文件路径，可由配置项覆盖
     */
    public NarrativeRuntimeSettingsService(ObjectMapper mapper, @Value("${aigame.narrative.settings-file:./data/narrative-settings.json}") String file) { this.mapper = mapper; this.path = Path.of(file).toAbsolutePath().normalize(); }
    /** 读取持久化的运行时配置，文件不存在时返回启动配置默认值。 */
    public Settings read() {
        try { if (Files.exists(path)) return mapper.readValue(Files.readString(path), Settings.class); }
        catch (IOException exception) { throw new IllegalStateException("Unable to read narrative settings", exception); }
        return new Settings("", false, "", "", DEFAULT_MAX_TOKENS);
    }
    /** 校验并持久化运行时配置。 @param settings 用户提交的模型配置 */
    public Settings write(Settings settings) {
        if (settings == null) throw GameRequestException.invalid("settings is required");
        if (settings.enabled() && settings.apiKey().isBlank()) throw GameRequestException.invalid("API key is required when external model is enabled");
        try { Files.createDirectories(path.getParent()); Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(settings)); return settings; }
        catch (IOException exception) { throw new IllegalStateException("Unable to write narrative settings", exception); }
    }
    /**
     * 运行时模型配置字段。
     * @param apiKey 模型服务的 API key，关闭外部模型时可为空
     * @param enabled 是否启用外部模型
     * @param baseUrl 模型服务基础地址，为空时使用默认地址
     * @param model 模型标识，为空时使用默认模型
     * @param maxTokens 单次响应的输出 token 上限，为空或越界时收敛到允许区间
     */
    public record Settings(String apiKey, boolean enabled, String baseUrl, String model, Integer maxTokens) {
        /** 规范化配置：字符串字段不允许为 null，输出上限缺失或越界时收敛到允许区间。 */
        public Settings {
            apiKey = apiKey == null ? "" : apiKey;
            baseUrl = baseUrl == null ? "" : baseUrl;
            model = model == null ? "" : model;
            maxTokens = maxTokens == null || maxTokens < MIN_ALLOWED_MAX_TOKENS ? DEFAULT_MAX_TOKENS
                    : Math.min(maxTokens, MAX_ALLOWED_MAX_TOKENS);
        }
    }
}
