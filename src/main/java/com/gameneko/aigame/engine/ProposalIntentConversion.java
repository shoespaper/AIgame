package com.gameneko.aigame.engine;

/** Result of interpreting an untrusted narrative proposal inside its selected engine. */
public record ProposalIntentConversion(ActionIntent intent, String rejection) {
    public ProposalIntentConversion {
        rejection = rejection == null ? "" : rejection;
        if ((intent == null) == rejection.isBlank()) {
            throw new IllegalArgumentException("Proposal conversion must be accepted or rejected");
        }
    }

    public static ProposalIntentConversion accept(ActionIntent intent) {
        return new ProposalIntentConversion(java.util.Objects.requireNonNull(intent), "");
    }

    public static ProposalIntentConversion reject(String reason) {
        return new ProposalIntentConversion(null, reason);
    }

    public boolean accepted() { return intent != null; }
}
