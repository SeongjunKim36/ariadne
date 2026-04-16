package com.ariadne.query;

public record CypherValidationResult(
        boolean valid,
        String error
) {

    public static CypherValidationResult success() {
        return new CypherValidationResult(true, null);
    }

    public static CypherValidationResult failure(String error) {
        return new CypherValidationResult(false, error);
    }
}
