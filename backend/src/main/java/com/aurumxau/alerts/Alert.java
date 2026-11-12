package com.aurumxau.alerts;

import com.aurumxau.model.Text;

import java.util.Map;

/** A user notification. {@code dedupeKey} guarantees each event is delivered once. */
public record Alert(String dedupeKey, String type, String severity, long timeMs, Text title, Text body,
                    String signalId, Map<String, Object> data) {
}
