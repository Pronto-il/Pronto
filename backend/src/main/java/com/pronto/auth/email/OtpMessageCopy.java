package com.pronto.auth.email;

import com.pronto.auth.entity.OtpPurpose;

/**
 * Subject and body copy for every OTP message, in Hebrew, in one place.
 *
 * <p>Shared by the email and SMS transports so the two cannot drift into telling a user different
 * stories about the same code, and kept out of the sender implementations so that swapping Amazon
 * SES for something else never touches customer-facing wording. It lives in this package, and
 * {@code auth.sms} imports it, rather than each transport owning its own copy — one OTP vocabulary,
 * one place to change it. The HTML email body is here for the same reason: a template inlined into
 * {@code SesEmailSender} would be a second copy of the same sentences.
 *
 * <p><b>The copy names the purpose on purpose.</b> An OTP email that just says "your code is
 * 123456" is a wasted signal: a user who receives a <em>login</em> code they did not ask for has
 * been handed proof that somebody else knows their password, and they can only act on that if the
 * message says which door the code opens. Every message therefore states what it authorizes, and
 * the three email purposes keep three distinct subject lines so that the warning is visible in an
 * inbox list without opening anything.
 *
 * <p><b>Every TTL sentence is formatted from {@link OtpPurpose#timeToLive()}</b>, never written out
 * as a literal. A copy edit that hardcoded "15 דקות" would keep reading correctly for verification
 * and start lying to every login recipient the moment the two windows diverged — which they already
 * have (10 minutes versus 15).
 */
public final class OtpMessageCopy {

    private OtpMessageCopy() {
    }

    public static String subject(OtpPurpose purpose) {
        return switch (purpose) {
            case EMAIL_VERIFICATION -> "קוד האימות שלך ב-Pronto";
            case EMAIL_LOGIN_OTP -> "קוד ההתחברות שלך ל-Pronto";
            case PASSWORD_RESET -> "קוד לאיפוס הסיסמה שלך ב-Pronto";
            // Neither of these is ever carried by email — both are SMS-channel purposes. Handled
            // rather than left to throw so that a future channel change cannot crash a send path.
            case PHONE_VERIFICATION, PHONE_LOGIN_OTP -> "קוד האימות שלך ב-Pronto";
        };
    }

    /**
     * The lead-in line, and the only sentence that differs between purposes. Everything after it —
     * the code, the validity sentence, the "not you?" line — is identical, so it is written once.
     */
    private static String emailLead(OtpPurpose purpose) {
        return switch (purpose) {
            case EMAIL_VERIFICATION -> "קוד האימות שלך ב-Pronto הוא:";
            case EMAIL_LOGIN_OTP -> "קוד ההתחברות שלך ל-Pronto הוא:";
            case PASSWORD_RESET -> "קוד לאיפוס הסיסמה שלך ב-Pronto הוא:";
            case PHONE_VERIFICATION, PHONE_LOGIN_OTP -> "קוד האימות שלך ב-Pronto הוא:";
        };
    }

    /**
     * Plain-text alternative part. Kept alongside the HTML body rather than replaced by it: a
     * text/plain part is what a client with HTML disabled, a screen reader in text mode, and every
     * spam filter that scores multipart messages actually read.
     */
    public static String emailBody(OtpPurpose purpose, String code) {
        return """
                שלום,

                %s

                %s

                הקוד תקף ל-%d דקות וניתן לשימוש פעם אחת בלבד.

                אם לא ביקשת את הקוד הזה, אפשר להתעלם מההודעה.

                Pronto
                """.formatted(emailLead(purpose), code, purpose.timeToLive().toMinutes());
    }

    /**
     * HTML alternative part, written right-to-left explicitly rather than hopefully.
     *
     * <p><b>Why the direction is stated four times over.</b> Hebrew copy renders left-aligned in a
     * mail client for a boringly mechanical reason: the client's own stylesheet supplies
     * {@code direction: ltr} on its message container, and heuristic first-strong-character
     * detection is not applied by most of them. So {@code dir} is set on {@code <html>}, on
     * {@code <body>}, on the layout table cell, and as an inline {@code direction}/{@code
     * text-align} pair on every text element. Gmail in particular discards {@code <head>} and any
     * {@code <style>} block outright, which is also why there is not one: nothing here depends on a
     * stylesheet surviving, and nothing is loaded from a URL.
     *
     * <p><b>The code itself is marked {@code dir="ltr"}.</b> Inside an RTL paragraph a bare digit
     * run is neutral-adjacent and can be reordered against neighbouring punctuation by the
     * bidirectional algorithm. It is isolated in its own centred block so that what the reader
     * copies is exactly the six digits in exactly the issued order.
     *
     * <p>Markup is deliberately unfashionable — nested tables, inline styles, no flexbox, no
     * {@code rem}, no web font — because that is the subset Outlook, Gmail's mobile apps and iOS
     * Mail all render the same way.
     */
    public static String emailHtmlBody(OtpPurpose purpose, String code) {
        String rtlText = "direction:rtl; text-align:right;";
        return """
                <!DOCTYPE html>
                <html lang="he" dir="rtl">
                <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%s</title>
                </head>
                <body dir="rtl" style="margin:0; padding:0; background-color:#f4f4f5; %s">
                <table role="presentation" dir="rtl" width="100%%" cellpadding="0" cellspacing="0" border="0"
                       style="background-color:#f4f4f5; width:100%%;">
                  <tr>
                    <td align="center" style="padding:24px 12px;">
                      <table role="presentation" dir="rtl" cellpadding="0" cellspacing="0" border="0"
                             style="width:100%%; max-width:480px; background-color:#ffffff; border-radius:12px;">
                        <tr>
                          <td dir="rtl" align="right"
                              style="padding:32px 24px; %s font-family:Arial,Helvetica,sans-serif;
                                     font-size:16px; line-height:1.7; color:#111827;">
                            <p style="margin:0 0 16px 0; %s">שלום,</p>
                            <p style="margin:0 0 24px 0; %s">%s</p>
                            <p dir="ltr" style="margin:0 0 24px 0; direction:ltr; text-align:center;
                                                font-size:32px; font-weight:bold; letter-spacing:4px;
                                                color:#111827;">%s</p>
                            <p style="margin:0 0 16px 0; %s">הקוד תקף ל-%d דקות וניתן לשימוש פעם אחת בלבד.</p>
                            <p style="margin:0 0 24px 0; %s color:#6b7280; font-size:14px;">אם לא ביקשת את הקוד הזה, אפשר להתעלם מההודעה.</p>
                            <p style="margin:0; %s font-weight:bold;">Pronto</p>
                          </td>
                        </tr>
                      </table>
                    </td>
                  </tr>
                </table>
                </body>
                </html>
                """.formatted(subject(purpose), rtlText, rtlText, rtlText, rtlText,
                emailLead(purpose), code, rtlText, purpose.timeToLive().toMinutes(), rtlText, rtlText);
    }

    /**
     * SMS body. Hebrew cannot be encoded in GSM-7, so every one of these is sent as UCS-2, where a
     * single segment holds 70 characters and a concatenated one only 67 — meaning one extra clause
     * does not cost a few bytes, it doubles the per-message price across the entire user base and
     * adds a reassembly step that can fail on the handset.
     *
     * <p>So the disclaimer sentence the email carries is not repeated here, and the copy is exactly
     * three facts: who is calling, the code, how long it lives. The longest purpose renders at 61
     * characters, comfortably inside one segment; {@code OtpMessageCopyTest} asserts that bound for
     * every purpose so a future copy edit cannot quietly cross it.
     */
    public static String smsBody(OtpPurpose purpose, String code) {
        String subject = switch (purpose) {
            case PHONE_LOGIN_OTP, EMAIL_LOGIN_OTP -> "קוד ההתחברות שלך";
            case PASSWORD_RESET -> "קוד לאיפוס הסיסמה שלך";
            case PHONE_VERIFICATION, EMAIL_VERIFICATION -> "קוד האימות שלך";
        };
        return "Pronto: %s הוא %s. הקוד תקף ל-%d דקות."
                .formatted(subject, code, purpose.timeToLive().toMinutes());
    }
}
