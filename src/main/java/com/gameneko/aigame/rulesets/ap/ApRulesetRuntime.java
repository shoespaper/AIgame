package com.gameneko.aigame.rulesets.ap;

import com.gameneko.aigame.engine.ActionValidation;
import com.gameneko.aigame.engine.GameState;
import com.gameneko.aigame.engine.RulesetResolution;
import com.gameneko.aigame.engine.RulesetRuntime;

/** AP rules are introduced as the first implementation of the engine contract. */
public final class ApRulesetRuntime implements RulesetRuntime<ApRulesetRuntime.ApState, ApRulesetRuntime.ApAction,
        ApRulesetRuntime.ApEvent, ApRulesetRuntime.ApProjection> {
    @Override
    public String id() {
        return "ap";
    }

    @Override
    public String version() {
        return "baseline";
    }

    @Override
    public ActionValidation validate(ApState state, ApAction action) {
        return ActionValidation.reject("AP rules are not migrated yet");
    }

    @Override
    public RulesetResolution<ApState, ApEvent> resolve(ApState state, ApAction action) {
        throw new UnsupportedOperationException("AP rules are not migrated yet");
    }

    @Override
    public ApProjection project(ApState state) {
        return new ApProjection(state);
    }

    public record ApState(long revision) implements GameState {
    }

    public record ApAction(String type) {
    }

    public record ApEvent(String type) {
    }

    public record ApProjection(ApState state) {
    }
}
