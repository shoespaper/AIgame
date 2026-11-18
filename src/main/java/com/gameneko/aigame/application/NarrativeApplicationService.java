package com.gameneko.aigame.application;

import com.gameneko.aigame.application.port.GameRepository;
import com.gameneko.aigame.content.ContentCatalog;
import com.gameneko.aigame.engine.GameInstance;
import com.gameneko.aigame.engine.ActionValidation;
import com.gameneko.aigame.narrative.NarrativeProposalCandidate;
import com.gameneko.aigame.narrative.NarrativeRequest;
import com.gameneko.aigame.narrative.NarrativeResponse;
import com.gameneko.aigame.narrative.NarrativeSnapshotFactory;
import com.gameneko.aigame.narrative.NarrativeMediaRequest;
import com.gameneko.aigame.narrative.NarrationService;
import com.gameneko.aigame.narrative.ProposalConversion;
import com.gameneko.aigame.narrative.ProposalConversionService;
import com.gameneko.aigame.rulesets.ap.ApRulesetRuntime;
import org.springframework.stereotype.Service;

import java.util.List;

/** Orchestrates narration and proposal application while keeping authoritative state server-owned. */
@Service
public final class NarrativeApplicationService {
    private final GameRepository repository;
    private final ContentCatalog contentCatalog;
    private final NarrationService narrationService;
    private final ProposalConversionService proposalConversionService;
    private final GameActionService gameActionService;

    public NarrativeApplicationService(GameRepository repository, ContentCatalog contentCatalog,
                                       NarrationService narrationService,
                                       ProposalConversionService proposalConversionService,
                                       GameActionService gameActionService) {
        this.repository = repository;
        this.contentCatalog = contentCatalog;
        this.narrationService = narrationService;
        this.proposalConversionService = proposalConversionService;
        this.gameActionService = gameActionService;
    }

    public NarrativeResponse narrate(String gameId, long expectedRevision, String playerInput, String locale) {
        GameInstance<ApRulesetRuntime.ApState> instance = load(gameId, expectedRevision);
        var snapshot = NarrativeSnapshotFactory.from(instance, List.of(), instance.worldState().worldEvents());
        return narrationService.narrate(new NarrativeRequest(gameId, expectedRevision, playerInput, locale, snapshot));
    }

    public NarrativeResponse narrateAfter(GameActionService.AppliedAction applied,
                                          String playerInput, String locale) {
        var instance = applied.instance();
        var snapshot = NarrativeSnapshotFactory.from(instance, applied.events(), applied.worldEvents());
        return narrationService.narrate(new NarrativeRequest(instance.gameId(), instance.revision(), playerInput, locale, snapshot));
    }

    public NarratedAction narrateAction(String gameId, long expectedRevision,
                                        ApRulesetRuntime.ApAction action,
                                        String playerInput, String locale) {
        var applied = gameActionService.applyDetailed(gameId, expectedRevision, action);
        NarrativeResponse response = narrateAfter(applied, playerInput, locale);
        var definition = contentCatalog.world(applied.instance().worldId());
        List<ProposalPreview> previews = new java.util.ArrayList<>();
        for (int i = 0; i < response.proposals().size(); i++) {
            NarrativeProposalCandidate candidate = response.proposals().get(i);
            ProposalConversion conversion = proposalConversionService.convert(candidate, gameId,
                    applied.instance().revision(), i, applied.instance().worldState().day(), definition);
            previews.add(new ProposalPreview(candidate.type(), conversion.converted(), conversion.rejection()));
        }
        return new NarratedAction(applied.instance(), response.narration(), response.mediaRequests(),
                response.proposals(), previews, NarrativeSnapshotFactory.from(applied.instance(),
                applied.events(), applied.worldEvents()).confirmedEvents());
    }

    public GameInstance<?> applyProposal(String gameId, long expectedRevision,
                                         NarrativeProposalCandidate candidate, int proposalIndex) {
        GameInstance<ApRulesetRuntime.ApState> instance = load(gameId, expectedRevision);
        var definition = contentCatalog.world(instance.worldId());
        ProposalConversion conversion = proposalConversionService.convert(candidate, gameId, expectedRevision,
                proposalIndex, instance.worldState().day(), definition);
        if (!conversion.converted()) throw new IllegalArgumentException("AI proposal rejected: " + conversion.rejection());
        if (conversion.action() != null) return gameActionService.apply(gameId, expectedRevision, conversion.action());
        return gameActionService.applyWorldAction(gameId, expectedRevision, conversion.worldAction());
    }

    public record ProposalPreview(String type, boolean convertible, String reason) {}
    public record NarratedAction(GameInstance<?> instance, String narration,
                                 List<NarrativeMediaRequest> mediaRequests,
                                 List<NarrativeProposalCandidate> proposals,
                                 List<ProposalPreview> proposalPreviews,
                                 List<com.gameneko.aigame.narrative.NarrativeSnapshot.ConfirmedEvent> confirmedEvents) {
        public NarratedAction {
            mediaRequests = List.copyOf(mediaRequests);
            proposals = List.copyOf(proposals);
            proposalPreviews = List.copyOf(proposalPreviews);
            confirmedEvents = List.copyOf(confirmedEvents);
        }
    }

    public ProposalPreview previewProposal(String gameId, long expectedRevision,
                                           NarrativeProposalCandidate candidate, int proposalIndex) {
        GameInstance<ApRulesetRuntime.ApState> instance = load(gameId, expectedRevision);
        var definition = contentCatalog.world(instance.worldId());
        var conversion = proposalConversionService.convert(candidate, gameId, expectedRevision,
                proposalIndex, instance.worldState().day(), definition);
        if (!conversion.converted()) return new ProposalPreview(candidate.type(), false, conversion.rejection());
        if (conversion.action() != null) {
            ActionValidation validation = new ApRulesetRuntime().validate(instance.rulesetState(), conversion.action(), definition);
            return new ProposalPreview(candidate.type(), validation.accepted(), validation.reason());
        }
        if (conversion.worldAction() instanceof com.gameneko.aigame.engine.WorldAction.ScheduleEvent schedule
                && instance.worldState().deadlineEvents().stream().anyMatch(event -> event.id().equals(schedule.event().id()))) {
            return new ProposalPreview(candidate.type(), false, "timed_event_id_already_exists");
        }
        return new ProposalPreview(candidate.type(), true, "");
    }

    private GameInstance<ApRulesetRuntime.ApState> load(String gameId, long expectedRevision) {
        GameInstance<?> loaded = repository.load(gameId).orElseThrow(() -> new IllegalArgumentException("Save not found: " + gameId));
        if (expectedRevision != loaded.revision()) throw new IllegalArgumentException("Revision mismatch: expected " + expectedRevision + ", current " + loaded.revision());
        if (!(loaded.rulesetState() instanceof ApRulesetRuntime.ApState)) throw new IllegalArgumentException("Game does not contain AP state");
        @SuppressWarnings("unchecked") GameInstance<ApRulesetRuntime.ApState> typed = (GameInstance<ApRulesetRuntime.ApState>) loaded;
        return typed;
    }
}
