package com.pronto.common.exception;

import software.amazon.awssdk.awscore.exception.AwsServiceException;

/**
 * Turns an AWS SDK exception into the shortest string that is still useful to an operator, and
 * nothing more.
 *
 * <p><b>Why not {@code e.toString()}.</b> AWS service exception messages routinely quote the input
 * that caused them. SNS's {@code InvalidParameterException} says things like "Invalid parameter:
 * PhoneNumber Reason: +9725… is not valid", and SES rejection messages can quote the recipient
 * address. Logging the raw message therefore writes customer contact details into every log
 * aggregator the application ships to — for the one class of failure that happens most often, on the
 * one code path where the destination is by definition a real person's phone or inbox.
 *
 * <p>What is kept is what actually distinguishes the failure modes an operator has to tell apart:
 * the exception type, the AWS error code ({@code Throttling} vs {@code InvalidParameter} vs
 * {@code AccountSuspended}) and the request id to quote at AWS support. None of those contain
 * customer data, and none of them contain the message body — which, for an OTP send, is the code
 * itself.
 */
public final class AwsErrorSummary {

    private AwsErrorSummary() {
    }

    public static String of(Throwable error) {
        if (error instanceof AwsServiceException awsError) {
            String errorCode = awsError.awsErrorDetails() == null
                    ? "unknown" : awsError.awsErrorDetails().errorCode();
            return error.getClass().getSimpleName()
                    + " awsErrorCode=" + errorCode
                    + " requestId=" + awsError.requestId()
                    + " status=" + awsError.statusCode();
        }
        // A client-side failure (timeout, connection reset, serialization). Its message is generated
        // by the SDK rather than by the service, but it can still embed request detail, so only the
        // type is reported.
        return error.getClass().getSimpleName();
    }
}
