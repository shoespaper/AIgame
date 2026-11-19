package com.gameneko.aigame.api;

import com.gameneko.aigame.application.InitializationAnswerService;
import com.gameneko.aigame.engine.GameSelection;
import com.gameneko.aigame.engine.InitializationSchema;
import org.springframework.web.bind.annotation.*;

/** HTTP boundary for schema-driven initialization questions and answer validation. */
@RestController
@RequestMapping("/api/games/initialization")
public final class GameInitializationController {
    private final InitializationAnswerService answers;

    public GameInitializationController(InitializationAnswerService answers) { this.answers = answers; }

    @GetMapping("/schema")
    public InitializationSchema schema(@RequestParam String worldId, @RequestParam(required = false) String engineId) {
        return answers.schema(new GameSelection(worldId, engineId));
    }

    @PostMapping("/validate")
    public InitializationValidationResponse validate(@RequestBody InitializationValidationRequest request) {
        if (request == null || request.worldId() == null) throw new IllegalArgumentException("worldId is required");
        var result = answers.parseAndValidate(new GameSelection(request.worldId(), request.engineId()), request.input());
        return new InitializationValidationResponse(result.accepted(), result.errors());
    }
}
