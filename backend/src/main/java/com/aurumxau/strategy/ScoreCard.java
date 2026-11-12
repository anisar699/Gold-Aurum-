package com.aurumxau.strategy;

import com.aurumxau.model.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Transparent rules-based setup-quality score (0-100). This is NOT a probability of profit.
 * Components: HTF alignment 25, location 20, setup 15, trigger 15, momentum 10, volatility 5, cost 5, session 5.
 */
public record ScoreCard(List<Item> items, List<Item> penalties, int total) {

    public record Item(String component, double points, double max, Text note) {}

    public static final class Builder {
        private final List<Item> items = new ArrayList<>();
        private final List<Item> penalties = new ArrayList<>();

        public Builder add(String component, double points, double max, Text note) {
            double p = Math.max(0, Math.min(max, points));
            items.add(new Item(component, Math.round(p * 10) / 10.0, max, note));
            return this;
        }

        public Builder penalty(String component, double points, Text note) {
            penalties.add(new Item(component, -Math.abs(points), 0, note));
            return this;
        }

        public ScoreCard build() {
            double s = items.stream().mapToDouble(Item::points).sum() + penalties.stream().mapToDouble(Item::points).sum();
            int total = (int) Math.round(Math.max(0, Math.min(100, s)));
            return new ScoreCard(List.copyOf(items), List.copyOf(penalties), total);
        }
    }
}
