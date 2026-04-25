package com.executionagent.exceptions;

/** Raised when agent exhausts step budget without accomplishing goals. */
public class BudgetExhaustedException extends RuntimeException {
    public BudgetExhaustedException(String message) {
        super(message);
    }
}
