package com.leash.policy;

/** One entry of the Viseca mandate's hard_rules[]. No extra keys allowed by the API; nulls are omitted on the wire. */
public record Rule(String field, String operator, Object value, String currency, String scope, Integer periodDays) {}
