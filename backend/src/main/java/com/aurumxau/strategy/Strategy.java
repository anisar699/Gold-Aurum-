package com.aurumxau.strategy;

import com.aurumxau.model.Direction;
import com.aurumxau.model.Text;

import java.util.List;
import java.util.Optional;

/** A deterministic setup detector. Returns a raw candidate; scoring and filtering happen in DecisionEngine. */
public interface Strategy {
    String id();

    String name();

    Optional<Candidate> evaluate(StrategyContext ctx, Direction direction);

    /**
     * @param structuralStop exit-side price beyond the invalidating structure (buffer applied)
     * @param setupPoints    0..15 clarity of the M5 pattern
     * @param triggerPoints  0..15 quality of the M1 confirmation candle
     * @param locationPrice  price used to measure M15 location confluence
     * @param patternKey     stable key used to deduplicate the same pattern across evaluations
     */
    record Candidate(String strategy, String strategyName, Direction direction, double structuralStop,
                     double setupPoints, double triggerPoints, double locationPrice, long setupTimeMs,
                     long triggerTimeMs, String patternKey, List<Text> reasons, Text invalidation,
                     Text requiredConfirmation) {}
}
