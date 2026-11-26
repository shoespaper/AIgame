package com.gameneko.aigame.api;

import com.gameneko.aigame.application.GameRequestException;
import com.gameneko.aigame.application.GameSaveService;
import com.gameneko.aigame.engine.*;
import org.springframework.stereotype.Component;
import java.util.*;

/** 将 HTTP 动作请求转换为引擎中立的意图或世界动作。 */
@Component
public final class GameActionMapper {
    private final GameSaveService saveService;
    /** 创建动作映射器。
     * @param saveService 读取当前权威世界日期的存档服务
     */
    public GameActionMapper(GameSaveService saveService) {
        this.saveService = saveService;
    }

    /** 将请求分发为世界动作或供选中引擎验证的结构化意图。
     * @param gameId 游戏存档标识
     * @param r 客户端提交的动作参数
     */
    public MappedGameAction map(String gameId, GameActionRequest r) {
        if (r == null || r.type() == null || r.type().isBlank()) throw GameRequestException.invalid("Action type is required");
        long day = saveService.load(gameId).worldState().day();
        return switch (r.type().trim().toLowerCase(Locale.ROOT)) {
            case "schedule_timed_event" -> {
                long deadlineDays = integer(r, "deadlineDays", 14);
                if (deadlineDays <= 0) throw GameRequestException.invalid("Deadline must be positive");
                if (day > Long.MAX_VALUE - deadlineDays) throw GameRequestException.invalid("Deadline is out of range");
                TimedEvent event = new TimedEvent(eventId(r), "foreshadow", text(r, "eventDescription"),
                        text(r, "eventAnswer"), text(r, "eventConsequence"), day, day + deadlineDays,
                        deadlineDays, TimedEvent.Status.ACTIVE, text(r, "materialId"));
                yield MappedGameAction.world(new WorldAction.ScheduleEvent(event));
            }
            case "resolve_timed_event" -> MappedGameAction.world(new WorldAction.ResolveEvent(eventId(r), text(r, "outcome")));
            case "set_price_modifier" -> MappedGameAction.world(new WorldAction.SetPriceModifier(new PriceModifier(PriceModifier.category(text(r, "priceCategory")), pricePercent(r), "action", integer(r, "expiresDay", 0))));
            case "remove_price_modifier" -> MappedGameAction.world(new WorldAction.RemovePriceModifier(PriceModifier.category(text(r, "priceCategory"))));
            default -> MappedGameAction.intent(intent(r));
        };
    }
    /** 将请求中的有类型参数转换为引擎意图。
     * @param r 含动作类型、目标与参数列表的请求
     */
    private ActionIntent intent(GameActionRequest r) {
        List<ActionIntent.Argument> a = new ArrayList<>();
        for (ActionArgumentRequest argument : r.arguments()) {
            if (argument == null || argument.name() == null || argument.name().isBlank()) throw GameRequestException.invalid("Argument name is required");
            InitializationValue value = switch (argument.type() == null ? "text" : argument.type().toLowerCase(Locale.ROOT)) {
                case "integer" -> argument.integer() == null ? null : new InitializationValue.IntegerNumber(argument.integer());
                case "decimal" -> argument.decimal() == null ? null : new InitializationValue.DecimalNumber(argument.decimal());
                case "flag", "boolean" -> argument.flag() == null ? null : new InitializationValue.Flag(argument.flag());
                case "text" -> argument.text() == null ? null : new InitializationValue.Text(argument.text());
                default -> throw GameRequestException.invalid("Unsupported argument type: " + argument.type());
            };
            if (value == null) throw GameRequestException.invalid("Argument value is required: " + argument.name());
            a.add(new ActionIntent.Argument(argument.name(), value));
        }
        return new ActionIntent(r.type(), r.target(), a);
    }

    /** 读取世界动作要求提供的事件 ID。
     * @param request 当前动作参数
     */
    private static String eventId(GameActionRequest request) {
        String id = text(request, "eventId");
        if (id.isBlank()) throw GameRequestException.invalid("Timed event id is required");
        return id;
    }

    /** 校验价格百分比在 int 允许的非负范围内。
     * @param request 当前动作参数
     */
    private static int pricePercent(GameActionRequest request) {
        long percent = integer(request, "pricePercent", 0);
        if (percent < 0 || percent > Integer.MAX_VALUE) throw GameRequestException.invalid("Price multiplier is out of range");
        return (int) percent;
    }

    /** 读取指定文本参数，缺省时返回空字符串。
     * @param request 当前动作请求
     * @param name 参数名称
     */
    private static String text(GameActionRequest request, String name) {
        return request.arguments().stream().filter(argument -> name.equals(argument.name()) && "text".equalsIgnoreCase(argument.type()))
                .map(ActionArgumentRequest::text).filter(Objects::nonNull).findFirst().orElse("");
    }

    /** 读取指定整数参数并应用缺省值。
     * @param request 当前动作请求
     * @param name 参数名称
     * @param fallback 参数缺失时使用的默认数值
     */
    private static long integer(GameActionRequest request, String name, long fallback) {
        return request.arguments().stream().filter(argument -> name.equals(argument.name()) && "integer".equalsIgnoreCase(argument.type()))
                .map(ActionArgumentRequest::integer).filter(Objects::nonNull).findFirst().orElse(fallback);
    }
}
