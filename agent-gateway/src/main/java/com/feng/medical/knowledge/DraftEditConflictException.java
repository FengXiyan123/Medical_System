package com.feng.medical.knowledge;

/** The editor rejected an obsolete expected_edit_revision. */
public final class DraftEditConflictException extends IllegalStateException {
    public DraftEditConflictException(String message) {
        super(message);
    }
}
