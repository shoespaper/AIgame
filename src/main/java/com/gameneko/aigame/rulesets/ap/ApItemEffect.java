package com.gameneko.aigame.rulesets.ap;

import java.util.List;

/** Explicit non-scriptable effects available on AP inventory items. */
public record ApItemEffect(int hpRecovery, int spRecovery, boolean clearStatuses,
                           List<StatusApplication> statusApplications,
                           int battlePowerBonus, int battleGuardBonus, int durationDays) {
    public ApItemEffect {
        if (hpRecovery < 0 || spRecovery < 0 || durationDays < 0
                || battlePowerBonus < 0 || battleGuardBonus < 0) {
            throw new IllegalArgumentException("Item effects must not be negative");
        }
        statusApplications = statusApplications == null ? List.of() : List.copyOf(statusApplications);
    }

    public static ApItemEffect none() { return new ApItemEffect(0, 0, false, List.of(), 0, 0, 0); }
    public boolean usable() {
        return hpRecovery > 0 || spRecovery > 0 || clearStatuses || !statusApplications.isEmpty()
                || battlePowerBonus > 0 || battleGuardBonus > 0;
    }

    public record StatusApplication(String name, int duration, String penalty) {
        public StatusApplication {
            if (name == null || name.isBlank() || duration <= 0) throw new IllegalArgumentException("Invalid item status effect");
            penalty = penalty == null ? "" : penalty;
        }
    }
}
