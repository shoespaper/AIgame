package com.gameneko.aigame.narrative;

import com.gameneko.aigame.rulesets.ap.ApRulesetRuntime;
import com.gameneko.aigame.engine.WorldAction;

/** Either a closed AP action ready for ruleset validation or a rejected candidate. */
public record ProposalConversion(NarrativeProposalCandidate candidate,
                                 ApRulesetRuntime.ApAction action, WorldAction worldAction,
                                 String rejection) {
    public ProposalConversion(NarrativeProposalCandidate candidate,
                              ApRulesetRuntime.ApAction action, String rejection) {
        this(candidate, action, null, rejection);
    }
    public ProposalConversion {
        rejection = rejection == null ? "" : rejection;
        int actions = (action == null ? 0 : 1) + (worldAction == null ? 0 : 1);
        if (actions > 1 || (actions == 1) == !rejection.isBlank()) {
            throw new IllegalArgumentException("Proposal conversion must be accepted or rejected");
        }
    }

    public boolean converted() { return action != null || worldAction != null; }
}
