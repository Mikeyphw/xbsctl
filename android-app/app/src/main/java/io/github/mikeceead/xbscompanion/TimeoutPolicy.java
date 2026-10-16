package io.github.mikeceead.xbscompanion;

/** Bounded execution defaults for root command runs. */
final class TimeoutPolicy {
    static final int READ_ONLY_SECONDS = 30;
    static final int STATE_CHANGE_SECONDS = 45;
    static final int DESTRUCTIVE_SECONDS = 60;
    static final int MIN_SECONDS = 5;
    static final int MAX_SECONDS = 300;

    final int seconds;

    private TimeoutPolicy(int seconds) {
        this.seconds = clamp(seconds);
    }

    static TimeoutPolicy forMutation(MutationClass mutationClass) {
        if (mutationClass == MutationClass.READ_ONLY) return new TimeoutPolicy(READ_ONLY_SECONDS);
        if (mutationClass == MutationClass.DESTRUCTIVE) return new TimeoutPolicy(DESTRUCTIVE_SECONDS);
        return new TimeoutPolicy(STATE_CHANGE_SECONDS);
    }

    static int clamp(int value) {
        if (value < MIN_SECONDS) return MIN_SECONDS;
        if (value > MAX_SECONDS) return MAX_SECONDS;
        return value;
    }
}
