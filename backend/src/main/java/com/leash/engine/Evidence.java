package com.leash.engine;

/** One fact that supported a decision. Sent to Viseca as an object (or as its toString() if the API insists on strings). */
public record Evidence(String source, String fact, String value, String note) {
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(fact);
        if (value != null) sb.append(": ").append(value);
        if (note != null) sb.append(" (").append(note).append(')');
        return sb.toString();
    }
}
