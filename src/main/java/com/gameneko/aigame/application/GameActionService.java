package com.gameneko.aigame.application;

import com.gameneko.aigame.application.port.GameRepository;
import com.gameneko.aigame.engine.ActionValidation;
import com.gameneko.aigame.engine.GameInstance;
import com.gameneko.aigame.engine.WorldState;
import com.gameneko.aigame.rulesets.ap.ApRulesetRuntime;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Loads, resolves, versions, and persists one authoritative AP action. */
@Service
public final class GameActionService {
    private final GameRepository repository;
    private final ApRulesetRuntime apRuntime;

    public GameActionService(GameRepository repository) {
        this.repository = repository;
        this.apRuntime = new ApRulesetRuntime();
    }

    public GameInstance<ApRulesetRuntime.ApState> apply(String gameId, long expectedRevision,
                                                         ApRulesetRuntime.ApAction action) {
        Objects.requireNonNull(action, "action");
        GameInstance<?> loaded = repository.load(gameId)
                .orElseThrow(() -> new IllegalArgumentException("Save not found: " + gameId));
        if (expectedRevision != loaded.revision()) {
            throw new IllegalArgumentException("Revision mismatch: expected " + expectedRevision
                    + ", current " + loaded.revision());
        }
        if (!(loaded.rulesetState() instanceof ApRulesetRuntime.ApState state)
                || !"ap".equals(loaded.rulesetId())) {
            throw new IllegalArgumentException("Game does not contain AP state");
        }
        ActionValidation validation = apRuntime.validate(state, action);
        if (!validation.accepted()) throw new IllegalArgumentException(validation.reason());
        var resolution = apRuntime.resolve(state, action);
        long nextRevision = loaded.revision() + 1;
        WorldState nextWorld = advanceWorld(loaded.worldState(), nextRevision, action);
        GameInstance<ApRulesetRuntime.ApState> next = new GameInstance<>(loaded.gameId(), loaded.worldId(),
                loaded.contentVersion(), loaded.rulesetId(), loaded.rulesetVersion(),
                GameInstance.Lifecycle.ACTIVE, nextRevision, nextWorld, resolution.nextState());
        repository.save(next);
        return next;
    }

    private WorldState advanceWorld(WorldState world, long revision, ApRulesetRuntime.ApAction action) {
        boolean advanceDay = action instanceof ApRulesetRuntime.AdvanceDay;
        long day = advanceDay ? world.day() + 1 : world.day();
        long lastTickDay = advanceDay ? day : world.lastTickDay();
        return new WorldState(world.worldId(), world.contentVersion(), revision, world.setting(),
                world.settingDescription(), world.location(), world.currentRegion(), world.mainQuest(), day,
                world.time(), world.season(), world.weather(), world.flags(), world.threatLevel(),
                world.threatCounter(), world.threatInterval(), world.threatHistory(), world.timedEvents(),
                world.incidentalEvents(), lastTickDay, world.crisisTriggered(), world.mainPlotStage(),
                world.mainPlotStages(), world.priceModifiers(), world.originScene());
    }
}
