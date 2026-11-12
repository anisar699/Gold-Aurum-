package com.aurumxau.news;

import com.aurumxau.model.Text;

/**
 * One economic-calendar event exactly as provided by the configured source. Values that the source does not
 * provide (e.g. "actual" in the FairEconomy weekly feed) are empty strings, never invented.
 */
public record EconomicEvent(
        String id,
        String title,
        String country,
        long timeMs,
        String impact,       // High | Medium | Low | Holiday
        String forecast,
        String previous,
        String actual,
        String source,
        String category,     // INFLATION | LABOR | FED | GROWTH | ACTIVITY | OTHER
        boolean goldRelevant,
        Text implication) {
}
