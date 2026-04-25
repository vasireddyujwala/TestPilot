package com.executionagent.exceptions;

/** Raised to stop the run loop cleanly when goals_accomplished is called. */
public class GoalsAccomplishedException extends RuntimeException {
    public GoalsAccomplishedException(String message) {
        super(message);
    }
    public GoalsAccomplishedException() {
        super("Goals accomplished");
    }
}
