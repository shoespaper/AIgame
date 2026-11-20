package com.gameneko.aigame.application;

import com.gameneko.aigame.application.port.GameRepository;
import com.gameneko.aigame.content.ContentCatalog;
import com.gameneko.aigame.engine.GameInstance;
import com.gameneko.aigame.engine.ActionValidation;
import com.gameneko.aigame.engine.ActionIntent;
import com.gameneko.aigame.narrative.NarrativeProposalCandidate;
import com.gameneko.aigame.narrative.NarrativeRequest;
import com.gameneko.aigame.narrative.NarrativeResponse;
import com.gameneko.aigame.narrative.NarrativeSnapshotFactory;
import com.gameneko.aigame.narrative.NarrativeMediaRequest;
import com.gameneko.aigame.narrative.NarrationService;
import com.gameneko.aigame.narrative.ProposalConversion;
import com.gameneko.aigame.application.port.NarrativeProposalConverter;
import com.gameneko.aigame.engine.GameEngineRuntime;
import org.springframework.stereotype.Service;

import java.util.List;

/** Orchestrates narration and proposal application while keeping authoritative state server-owned. */
@Service
public final class NarrativeApplicationService {
    private final GameRepository repository;
    private final ContentCatalog contentCatalog;
    private final NarrationService narrationService;
    private final NarrativeProposalConverter proposalConversionService;
    private final GameActionService gameActionService;

    public NarrativeApplicationService(GameRepository repository, ContentCatalog contentCatalog,
                                       NarrationService narrationService,
                                       NarrativeProposalConverter proposalConversionService,
                                       GameActionService gameActionService) {
        this.repository = repository;
        this.contentCatalog = contentCatalog;
        this.narrationService = narrationService;
        this.proposalConversionService = proposalConversionService;
        this.gameActionService = gameActionService;
    }

    public NarrativeResponse narrate(String gameId, long expectedRevision, String playerInput, String locale) {
        GameInstance<?> instance = load(gameId, expectedRevision);
        var snapshot = NarrativeSnapshotFactory.from(instance, List.of(), instance.worldState().worldEvents(), projection(instance));
        return narrationService.narrate(new NarrativeRequest(gameId, expectedRevision, playerInput, locale, snapshot));
    }

    public NarrativeResponse narrateAfter(GameActionService.AppliedAction applied,
                                          String playerInput, String locale) {
        var instance = applied.instance();
        var snapshot = NarrativeSnapshotFactory.fromConfirmed(applied.instance(), applied.events(), applied.worldEvents(), projection(applied.instance()));
        return narrationService.narrate(new NarrativeRequest(instance.gameId(), instance.revision(), playerInput, locale, snapshot));
    }

    public NarratedAction narrateAction(String gameId, long expectedRevision,
                                        ActionIntent action,
                                        String playerInput, String locale) {
        var applied = gameActionService.applyIntent(gameId, expectedRevision, action);
        NarrativeResponse response = narrateAfter(applied, playerInput, locale);
        var definition = contentCatalog.world(applied.instance().worldId());
        var runtime = runtimeFor(applied.instance());
        List<ProposalPreview> previews = new java.util.ArrayList<>();
        for (int i = 0; i < response.proposals().size(); i++) {
            NarrativeProposalCandidate candidate = response.proposals().get(i);
            ProposalConversion conversion = proposalConversionService.convert(candidate, gameId,
                    applied.instance().revision(), i, applied.instance().worldState().day(), definition, runtime);
            previews.add(new ProposalPreview(candidate.type(), conversion.converted(), conversion.rejection()));
        }
        return new NarratedAction(applied.instance(), response.narration(), response.mediaRequests(),
                response.proposals(), previews, NarrativeSnapshotFactory.fromConfirmed(applied.instance(),
                applied.events(), applied.worldEvents(), projection(applied.instance())).confirmedEvents());
    }

    public GameInstance<?> applyProposal(String gameId, long expectedRevision,
                                         NarrativeProposalCandidate candidate, int proposalIndex) {
        GameInstance<?> instance = load(gameId, expectedRevision);
        var definition = contentCatalog.world(instance.worldId());
        var runtime = runtimeFor(instance);
        ProposalConversion conversion = proposalConversionService.convert(candidate, gameId, expectedRevision,
                proposalIndex, instance.worldState().day(), definition, runtime);
        if (!conversion.converted()) throw new IllegalArgumentException("AI proposal rejected: " + conversion.rejection());
        if (conversion.action() != null) return gameActionService.applyIntent(gameId, expectedRevision, conversion.action()).instance();
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
        GameInstance<?> instance = load(gameId, expectedRevision);
        var definition = contentCatalog.world(instance.worldId());
        var runtime = runtimeFor(instance);
        var conversion = proposalConversionService.convert(candidate, gameId, expectedRevision,
                proposalIndex, instance.worldState().day(), definition, runtime);
        if (!conversion.converted()) return new ProposalPreview(candidate.type(), false, conversion.rejection());
        if (conversion.action() != null) {
            ActionValidation validation = gameActionService.validateIntent(gameId, expectedRevision, conversion.action());
            return new ProposalPreview(candidate.type(), validation.accepted(), validation.reason());
        }
        if (conversion.worldAction() instanceof com.gameneko.aigame.engine.WorldAction.ScheduleEvent schedule
                && instance.worldState().deadlineEvents().stream().anyMatch(event -> event.id().equals(schedule.event().id()))) {
            return new ProposalPreview(candidate.type(), false, "timed_event_id_already_exists");
        }
        return new ProposalPreview(candidate.type(), true, "");
    }

    private GameInstance<?> load(String gameId, long expectedRevision) {
        GameInstance<?> loaded = repository.load(gameId).orElseThrow(() -> new IllegalArgumentException("Save not found: " + gameId));
        if (expectedRevision != loaded.revision()) throw new IllegalArgumentException("Revision mismatch: expected " + expectedRevision + ", current " + loaded.revision());
        return loaded;
    }

    private GameEngineRuntime<?, ?> runtimeFor(GameInstance<?> instance) {
        if (instance.rulesetId() == null) return null;
        return gameActionService.runtimeForNarration(instance, contentCatalog.world(instance.worldId()));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object projection(GameInstance<?> instance) {
        if (instance.rulesetId() == null) return null;
        GameEngineRuntime runtime = gameActionService.runtimeForNarration(instance, contentCatalog.world(instance.worldId()));
        return runtime.project(instance.rulesetState());
    }
}
