package com.gameneko.aigame.api;

import java.util.List;

/** Engine-neutral client command envelope. Arguments are validated by the selected engine. */
public record GameActionRequest(long expectedRevision, String type, String target,
                                List<ActionArgumentRequest> arguments) {
    public GameActionRequest {
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }
}
