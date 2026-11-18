package com.gameneko.aigame.narrative;

/** Builds narration from server-owned snapshots and confirmed events. */
public interface NarrationService {
    NarrativeResponse narrate(NarrativeRequest request);
}
