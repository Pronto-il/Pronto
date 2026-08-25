package com.pronto.auth.email;

import com.pronto.auth.entity.OtpChannel;
import com.pronto.auth.entity.OtpPurpose;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The customer-facing OTP copy, asserted rather than eyeballed.
 *
 * <p>Two classes of failure are worth a test here and neither is visible in review. The first is
 * <b>right-to-left rendering</b>: Hebrew that renders left-aligned looks like a styling nit in a
 * screenshot and like a broken product to an Israeli customer, and it regresses the instant somebody
 * simplifies the markup, because correct RTL in mail clients depends on the direction being restated
 * on every element rather than inherited. The second is <b>SMS length</b>: Hebrew is UCS-2, one
 * segment is 70 characters, and a single extra word silently doubles the per-message cost of the
 * whole platform without breaking anything a human would notice.
 *
 * <p>Every TTL assertion below reads {@link OtpPurpose#timeToLive()} rather than a literal, so these
 * tests keep meaning what they say if a window is ever retuned — except the two that deliberately
 * pin 15 and 10, which exist precisely to catch a copy edit that collapses the two windows into one
 * hardcoded number.
 */
class OtpMessageCopyTest {

    private static final String CODE = "483920";

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    // ---------------------------------------------------------------- HTML email: RTL rendering

    @ParameterizedTest
    @EnumSource(OtpPurpose.class)
    void htmlEmail_declaresHebrewAndRightToLeftOnTheDocumentItself(OtpPurpose purpose) {
        String html = OtpMessageCopy.emailHtmlBody(purpose, CODE);

        assertThat(html).contains("<html lang=\"he\" dir=\"rtl\">");
        assertThat(html).contains("<body dir=\"rtl\"");
    }

    @ParameterizedTest
    @EnumSource(OtpPurpose.class)
    void htmlEmail_restatesDirectionAndAlignmentOnEveryTextElement(OtpPurpose purpose) {
        // Not "contains text-align:right somewhere". Gmail supplies direction:ltr on its own message
        // container and does not run first-strong-character detection, so a single declaration on an
        // ancestor is exactly the thing that does not work. Seven elements carry the pair: <body>,
        // the layout cell, and the five right-aligned paragraphs (the code block is the LTR one).
        String html = OtpMessageCopy.emailHtmlBody(purpose, CODE);

        assertThat(occurrences(html, "direction:rtl; text-align:right;")).isEqualTo(7);
        assertThat(html).contains("<td dir=\"rtl\" align=\"right\"");
    }

    @ParameterizedTest
    @EnumSource(OtpPurpose.class)
    void htmlEmail_isolatesTheCodeLeftToRight(OtpPurpose purpose) {
        // Inside an RTL paragraph the bidirectional algorithm may reorder a digit run against
        // adjacent punctuation. The code gets its own explicitly-LTR block so what the reader copies
        // is the six digits in the order they were issued.
        String html = OtpMessageCopy.emailHtmlBody(purpose, CODE);

        assertThat(html).contains("<p dir=\"ltr\"").contains("direction:ltr; text-align:center;");
        assertThat(html).containsPattern("(?s)<p dir=\"ltr\".*?>" + CODE + "</p>");
    }

    @ParameterizedTest
    @EnumSource(OtpPurpose.class)
    void htmlEmail_dependsOnNothingItDoesNotCarry(OtpPurpose purpose) {
        // No stylesheet (Gmail discards <style> and <link> outright) and no remote asset: a tracking
        // pixel or hosted font would make the layout hostage to a network fetch and leak that the
        // message was opened.
        String html = OtpMessageCopy.emailHtmlBody(purpose, CODE);

        assertThat(html).doesNotContain("<style").doesNotContain("<link").doesNotContain("http");
    }

    // ------------------------------------------------------------------- Email: code and content

    @ParameterizedTest
    @EnumSource(OtpPurpose.class)
    void email_showsTheCodeExactlyOnceInBothParts(OtpPurpose purpose) {
        assertThat(occurrences(OtpMessageCopy.emailBody(purpose, CODE), CODE)).isEqualTo(1);
        assertThat(occurrences(OtpMessageCopy.emailHtmlBody(purpose, CODE), CODE)).isEqualTo(1);
        assertThat(OtpMessageCopy.subject(purpose)).doesNotContain(CODE);
    }

    @Test
    void email_verificationRendersTheFullTemplateWithTheFifteenMinuteWindow() {
        assertThat(OtpMessageCopy.subject(OtpPurpose.EMAIL_VERIFICATION))
                .isEqualTo("קוד האימות שלך ב-Pronto");
        assertThat(OtpMessageCopy.emailBody(OtpPurpose.EMAIL_VERIFICATION, CODE)).isEqualTo("""
                שלום,

                קוד האימות שלך ב-Pronto הוא:

                483920

                הקוד תקף ל-15 דקות וניתן לשימוש פעם אחת בלבד.

                אם לא ביקשת את הקוד הזה, אפשר להתעלם מההודעה.

                Pronto
                """);
    }

    @Test
    void email_loginRendersTheShorterTenMinuteWindowAndItsOwnWording() {
        assertThat(OtpMessageCopy.subject(OtpPurpose.EMAIL_LOGIN_OTP))
                .isEqualTo("קוד ההתחברות שלך ל-Pronto");
        assertThat(OtpMessageCopy.emailBody(OtpPurpose.EMAIL_LOGIN_OTP, CODE)).isEqualTo("""
                שלום,

                קוד ההתחברות שלך ל-Pronto הוא:

                483920

                הקוד תקף ל-10 דקות וניתן לשימוש פעם אחת בלבד.

                אם לא ביקשת את הקוד הזה, אפשר להתעלם מההודעה.

                Pronto
                """);
    }

    @Test
    void email_passwordResetSaysPasswordReset_notVerification() {
        String subject = OtpMessageCopy.subject(OtpPurpose.PASSWORD_RESET);
        String body = OtpMessageCopy.emailBody(OtpPurpose.PASSWORD_RESET, CODE);

        assertThat(subject).isEqualTo("קוד לאיפוס הסיסמה שלך ב-Pronto");
        assertThat(body).contains("קוד לאיפוס הסיסמה שלך ב-Pronto הוא:");
        assertThat(body).doesNotContain("קוד האימות").doesNotContain("קוד ההתחברות");
        assertThat(body).contains("הקוד תקף ל-15 דקות");
    }

    @Test
    void email_theThreeEmailPurposesKeepThreeDistinctSubjects() {
        // The security property this copy exists to preserve: a login code landing in an inbox the
        // owner did not ask it to is proof that somebody else has their password, and they can only
        // act on it if the subject line says so without the message being opened.
        assertThat(Arrays.stream(OtpPurpose.values())
                .filter(p -> p.channel() == OtpChannel.EMAIL)
                .map(OtpMessageCopy::subject)
                .distinct()
                .count()).isEqualTo(3);
    }

    @ParameterizedTest
    @EnumSource(OtpPurpose.class)
    void email_theStatedValidityIsAlwaysTheConfiguredOne(OtpPurpose purpose) {
        String expected = "הקוד תקף ל-" + purpose.timeToLive().toMinutes() + " דקות";

        assertThat(OtpMessageCopy.emailBody(purpose, CODE)).contains(expected);
        assertThat(OtpMessageCopy.emailHtmlBody(purpose, CODE)).contains(expected);
    }

    // ------------------------------------------------------------------------------------- SMS

    @Test
    void sms_verificationIsTheConciseForm() {
        assertThat(OtpMessageCopy.smsBody(OtpPurpose.PHONE_VERIFICATION, CODE))
                .isEqualTo("Pronto: קוד האימות שלך הוא 483920. הקוד תקף ל-15 דקות.");
    }

    @Test
    void sms_loginIsTheConciseFormWithItsOwnWindow() {
        assertThat(OtpMessageCopy.smsBody(OtpPurpose.PHONE_LOGIN_OTP, CODE))
                .isEqualTo("Pronto: קוד ההתחברות שלך הוא 483920. הקוד תקף ל-10 דקות.");
    }

    @Test
    void sms_passwordResetHasItsOwnWording() {
        // PASSWORD_RESET is an email-channel purpose and never reaches a handset today; the copy is
        // defined anyway so that a future channel change cannot produce a message calling a password
        // reset an "אימות".
        assertThat(OtpMessageCopy.smsBody(OtpPurpose.PASSWORD_RESET, CODE))
                .isEqualTo("Pronto: קוד לאיפוס הסיסמה שלך הוא 483920. הקוד תקף ל-15 דקות.");
    }

    @ParameterizedTest
    @EnumSource(OtpPurpose.class)
    void sms_fitsInOneUcs2SegmentForEveryPurpose(OtpPurpose purpose) {
        String body = OtpMessageCopy.smsBody(purpose, CODE);

        // Every character is in the BMP, so String.length() is exactly the UCS-2 code-unit count a
        // carrier counts. Without this the 70 below would be an assertion about Java chars rather
        // than about SMS segments.
        assertThat(body.codePoints().allMatch(c -> c <= 0xFFFF)).isTrue();
        assertThat(body.length())
                .as("%s renders %d UCS-2 characters; one segment holds 70, and a second segment "
                        + "doubles the cost of every OTP the platform sends", purpose, body.length())
                .isLessThanOrEqualTo(70);
    }

    @ParameterizedTest
    @EnumSource(OtpPurpose.class)
    void sms_showsTheCodeExactlyOnceAndCarriesNoDisclaimerParagraph(OtpPurpose purpose) {
        String body = OtpMessageCopy.smsBody(purpose, CODE);

        assertThat(occurrences(body, CODE)).isEqualTo(1);
        assertThat(body).startsWith("Pronto: ").doesNotContain("\n");
        assertThat(body).contains("הקוד תקף ל-" + purpose.timeToLive().toMinutes() + " דקות");
    }
}
