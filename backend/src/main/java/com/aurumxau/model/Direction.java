package com.aurumxau.model;

public enum Direction {
    LONG, SHORT;

    public int sign() {
        return this == LONG ? 1 : -1;
    }

    public Direction opposite() {
        return this == LONG ? SHORT : LONG;
    }
}
