package com.aurumxau.model;

/** A bilingual message: English and easy Roman Urdu. The app picks one by user setting. */
public record Text(String code, String en, String ur) {
    public static Text of(String code, String en, String ur) {
        return new Text(code, en, ur);
    }
}
