package com.gameneko.aigame.application.port;

import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.narrative.NarrativeProposalCandidate;
import com.gameneko.aigame.narrative.ProposalConversion;
import com.gameneko.aigame.engine.GameEngineRuntime;

public interface NarrativeProposalConverter {
    ProposalConversion convert(NarrativeProposalCandidate candidate, String gameId, long revision,
                               int proposalIndex, long currentDay, WorldDefinition world);

    ProposalConversion convert(NarrativeProposalCandidate candidate, String gameId, long revision,
                               int proposalIndex, long currentDay, WorldDefinition world,
                               GameEngineRuntime<?, ?> engine);
}
