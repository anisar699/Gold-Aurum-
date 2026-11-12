package com.aurumxau.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setSerializationInclusion(JsonInclude.Include.ALWAYS);

    /**
     * Mapper for API responses: non-finite numbers (NaN, Infinity) are written as JSON null so clients
     * never receive invalid numbers. {@link #MAPPER} (storage) keeps them as strings for exact round-trips.
     */
    public static final ObjectMapper API = MAPPER.copy().registerModule(new com.fasterxml.jackson.databind.module.SimpleModule()
            .addSerializer(Double.class, new NonFinite())
            .addSerializer(double.class, new NonFinite()));

    private static final class NonFinite extends com.fasterxml.jackson.databind.ser.std.StdSerializer<Double> {
        NonFinite() { super(Double.class); }

        @Override
        public void serialize(Double v, com.fasterxml.jackson.core.JsonGenerator g, com.fasterxml.jackson.databind.SerializerProvider p)
                throws java.io.IOException {
            if (v == null || v.isNaN() || v.isInfinite()) g.writeNull();
            else g.writeNumber(v);
        }
    }

    private Json() {}

    public static String api(Object o) {
        try {
            return API.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON serialisation failed", e);
        }
    }

    public static String write(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON serialisation failed", e);
        }
    }

    public static JsonNode read(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid JSON: " + e.getOriginalMessage());
        }
    }

    public static <T> T read(String s, Class<T> type) {
        try {
            return MAPPER.readValue(s, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid JSON: " + e.getOriginalMessage());
        }
    }
}
