package com.gameneko.aigame.narrative;

import com.gameneko.aigame.engine.ActionIntent;
import com.gameneko.aigame.engine.WorldAction;

/** Either a generic action/world command ready for validation or a rejected candidate. */
public record ProposalConversion(NarrativeProposalCandidate candidate,
                                 ActionIntent action, WorldAction worldAction,
                                 String rejection) {
    public ProposalConversion(NarrativeProposalCandidate candidate,
                              ActionIntent action, String rejection) {
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
