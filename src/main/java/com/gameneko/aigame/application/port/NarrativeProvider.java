package com.gameneko.aigame.application.port;

import com.gameneko.aigame.narrative.NarrativePrompt;
import com.gameneko.aigame.narrative.NarrativeResponse;

/** Application-owned port for an external or local narrative provider. */
public interface NarrativeProvider {
    NarrativeResponse respond(NarrativePrompt prompt);
}
