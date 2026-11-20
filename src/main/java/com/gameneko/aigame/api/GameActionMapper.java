package com.gameneko.aigame.api;

import com.gameneko.aigame.application.GameSaveService;
import com.gameneko.aigame.engine.*;
import org.springframework.stereotype.Component;
import java.util.*;

/** Converts HTTP input into engine-neutral intents or world commands. */
@Component
public final class GameActionMapper {
    private final GameSaveService saveService;
    public GameActionMapper(GameSaveService saveService) { this.saveService = saveService; }
    public MappedGameAction map(String gameId, GameActionRequest r) {
        if (r == null || r.type() == null) throw new IllegalArgumentException("Action type is required");
        long day = saveService.load(gameId).worldState().day();
        return switch (r.type().trim().toLowerCase(Locale.ROOT)) {
            case "schedule_timed_event" -> { long d = integer(r, "deadlineDays", 14); if (d <= 0) throw new IllegalArgumentException("Deadline must be positive"); yield MappedGameAction.world(new WorldAction.ScheduleEvent(new TimedEvent(text(r, "eventId"), "foreshadow", text(r, "eventDescription"), text(r, "eventAnswer"), text(r, "eventConsequence"), day, Math.addExact(day, d), d, TimedEvent.Status.ACTIVE, WorldEffects.none()))); }
            case "resolve_timed_event" -> MappedGameAction.world(new WorldAction.ResolveEvent(text(r, "eventId"), text(r, "outcome")));
            case "set_price_modifier" -> MappedGameAction.world(new WorldAction.SetPriceModifier(new PriceModifier(PriceModifier.category(text(r, "priceCategory")), (int) integer(r, "pricePercent", 0), "action", integer(r, "expiresDay", 0))));
            case "remove_price_modifier" -> MappedGameAction.world(new WorldAction.RemovePriceModifier(PriceModifier.category(text(r, "priceCategory"))));
            default -> MappedGameAction.intent(intent(r));
        };
    }
    private ActionIntent intent(GameActionRequest r) {
        List<ActionIntent.Argument> a = new ArrayList<>();
        for (ActionArgumentRequest argument : r.arguments()) {
            if (argument == null || argument.name() == null || argument.name().isBlank()) throw new IllegalArgumentException("Argument name is required");
            InitializationValue value = switch (argument.type() == null ? "text" : argument.type().toLowerCase(Locale.ROOT)) {
                case "integer" -> argument.integer() == null ? null : new InitializationValue.IntegerNumber(argument.integer());
                case "decimal" -> argument.decimal() == null ? null : new InitializationValue.DecimalNumber(argument.decimal());
                case "flag", "boolean" -> argument.flag() == null ? null : new InitializationValue.Flag(argument.flag());
                case "text" -> argument.text() == null ? null : new InitializationValue.Text(argument.text());
                default -> throw new IllegalArgumentException("Unsupported argument type: " + argument.type());
            };
            if (value == null) throw new IllegalArgumentException("Argument value is required: " + argument.name());
            a.add(new ActionIntent.Argument(argument.name(), value));
        }
        return new ActionIntent(r.type(), r.target(), a);
    }

    private static String text(GameActionRequest request, String name) {
        return request.arguments().stream().filter(argument -> name.equals(argument.name()) && "text".equalsIgnoreCase(argument.type()))
                .map(ActionArgumentRequest::text).filter(Objects::nonNull).findFirst().orElse("");
    }

    private static long integer(GameActionRequest request, String name, long fallback) {
        return request.arguments().stream().filter(argument -> name.equals(argument.name()) && "integer".equalsIgnoreCase(argument.type()))
                .map(ActionArgumentRequest::integer).filter(Objects::nonNull).findFirst().orElse(fallback);
    }
}
