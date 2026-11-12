package com.aurumxau.strategy;

import com.aurumxau.store.KeyValueStore;

import java.sql.SQLException;
import java.util.List;

/** Persisted, validated strategy/risk settings. */
public final class SettingsService {
    private static final String KEY = "strategy.settings";
    private final KeyValueStore kv;
    private volatile StrategySettings current;

    public SettingsService(KeyValueStore kv) throws SQLException {
        this.kv = kv;
        StrategySettings s = kv.get(KEY, StrategySettings.class, null);
        this.current = s == null || !s.validate().isEmpty() ? StrategySettings.defaults() : s;
    }

    public StrategySettings get() {
        return current;
    }

    /** @return validation errors; settings are saved only when the list is empty */
    public synchronized List<String> update(StrategySettings s) throws SQLException {
        List<String> errors = s.validate();
        if (errors.isEmpty()) {
            kv.put(KEY, s);
            current = s;
        }
        return errors;
    }
}
