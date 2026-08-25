package com.pronto.auth.dto;

/**
 * What the client must do next. Every multi-step auth endpoint returns one of these, and the client
 * branches on it rather than on which endpoint it happened to call.
 *
 * <p><b>Why the flows are expressed as a state machine at all.</b> Registration and login are no
 * longer single round trips: registration is three (details → email code → phone code) and login is
 * two (password → code), and either can be interrupted — a closed tab, an expired code, an account
 * that stopped halfway through registration a week ago. Handing the client the next state instead
 * of letting it infer one from response shapes means there is exactly one place where "what happens
 * after this step" is decided, and it is the server.
 */
public enum AuthNextStep {

    /**
     * Redeem the accompanying challenge at {@code POST /api/auth/verify-email}.
     *
     * <p>Returned by registration, and also by {@code POST /api/auth/login} when a correct password
     * belongs to an account that never finished verifying its email — which is how somebody who
     * abandoned registration halfway resumes it without a "resend my original link" mechanism.
     */
    VERIFY_EMAIL,

    /** Redeem the accompanying challenge at {@code POST /api/auth/verify-phone}. */
    VERIFY_PHONE,

    /** Redeem the accompanying challenge at {@code POST /api/auth/login/otp}. */
    LOGIN_OTP,

    /**
     * Nothing more to redeem; send the user to the login screen.
     *
     * <p>Reached when email verification completes on an account that has no phone number on file
     * at all — a pre-MS1 row. There is no phone to send a code to, so the account finishes
     * verifying what it can, authenticates by email, and is asked for a phone by the
     * {@code PHONE_VERIFICATION_REQUIRED} gate when it first tries to do something that needs one.
     */
    LOGIN,

    /** A session was issued. The response carries it. */
    AUTHENTICATED
}
