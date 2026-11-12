package io.github.aiassetmigrate.gui;

import java.util.Optional;

/**
 * The GUI's step-by-step workflow: the five steps in order, and which of them the user may open
 * given what has been done so far. Completed steps stay reachable (non-linear navigation); later
 * steps unlock only when their prerequisites exist (progressive enabling).
 */
final class WizardFlow {
    private WizardFlow() { }

    enum Step {
        PROJECT("Project"),
        REVIEW("Review"),
        TARGET("Target"),
        PLAN("Plan"),
        MIGRATE("Migrate");

        final String title;

        Step(String title) { this.title = title; }

        int number() { return ordinal() + 1; }

        Optional<Step> next() {
            return ordinal() + 1 < values().length ? Optional.of(values()[ordinal() + 1]) : Optional.empty();
        }

        Optional<Step> previous() {
            return ordinal() > 0 ? Optional.of(values()[ordinal() - 1]) : Optional.empty();
        }
    }

    /** What the user has done so far; every flag is derived from state the GUI already holds. */
    record Progress(boolean inventoryLoaded, boolean reviewVisited, boolean targetSelected,
                    boolean planApproved, boolean runFinished) {

        boolean unlocked(Step step) {
            return switch (step) {
                case PROJECT -> true;
                case REVIEW, TARGET -> inventoryLoaded;
                case PLAN -> inventoryLoaded && targetSelected;
                case MIGRATE -> planApproved || runFinished;
            };
        }

        boolean complete(Step step) {
            return switch (step) {
                case PROJECT -> inventoryLoaded;
                case REVIEW -> inventoryLoaded && reviewVisited;
                case TARGET -> inventoryLoaded && targetSelected;
                case PLAN -> planApproved || runFinished;
                case MIGRATE -> runFinished;
            };
        }

        /** Continue is offered only when the next step is open. */
        boolean canContinue(Step step) {
            return step.next().map(this::unlocked).orElse(false);
        }
    }
}
