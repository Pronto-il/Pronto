package com.pronto.notifications.service;

import com.pronto.notifications.entity.Notification;
import com.pronto.notifications.entity.NotificationMessageType;

/**
 * Which notifications may become an email, and what that email says.
 *
 * <p><b>Both questions are answered by the same {@code switch} on purpose.</b> A type with no
 * copy is a type that is not sent — there is no way to add one without the other, and no way to
 * add a new {@link NotificationMessageType} without this file failing to compile. The previous
 * arrangement had neither property: {@code NotificationServiceImpl} wrote an {@code EMAIL} row
 * for every type unconditionally and {@code EmailDispatchJob} rendered whatever arrived by
 * string-concatenating the enum constant, so every new status was a customer email by default
 * and its wording was its own internal name.
 *
 * <h2>What the allowlist is for</h2>
 *
 * <p>Email exists here for events the recipient could plausibly miss: another human did
 * something that changes their plans, or they have to act and the app is not open. Three types
 * are deliberately absent:
 *
 * <ul>
 *   <li>{@link NotificationMessageType#SOS_NO_PROFESSIONALS} and
 *       {@link NotificationMessageType#SOS_TEMPORARILY_UNAVAILABLE} — both are written by
 *       {@code SosDispatchService}'s failure branches, within seconds of the customer pressing
 *       the button, while they are watching the live SOS screen that already shows the same
 *       outcome. Neither describes anything a person did; they describe what Pronto's matching
 *       machinery could not do. The in-app row stays (it is the customer's record of the
 *       attempt) — only the email goes.</li>
 *   <li>{@link NotificationMessageType#EMAIL_VERIFICATION} — nothing writes it today, and if
 *       anything ever does it must not reach the inbox through this generic path.
 *       {@code auth.email.OtpMessageCopy} owns every word Pronto says about a verification
 *       code, deliberately, because those messages are a security signal and a second, blander
 *       one arriving alongside them would dilute it.</li>
 * </ul>
 *
 * <h2>Order ids</h2>
 *
 * <p>The reference line is rendered from {@code relatedOrderId} and omitted when it is absent,
 * which is the normal state of an SOS row: {@code Notification.forSosRequest} carries an
 * {@code sos_requests} id instead, because SOS offers are dispatched before any order exists.
 * Rendering {@code "Order #" + relatedOrderId} for those rows is what put a literal
 * {@code #null} in a customer's inbox. The SOS request id is not substituted in — it is an
 * internal key the customer has never seen and could not use.
 */
public final class NotificationEmailCopy {

    /** A rendered, ready-to-send message. Plain text — {@code sendOrderStatusEmail} has no HTML part. */
    public record EmailMessage(String subject, String body) {
    }

    /** Subject headline and body sentence for one message type, from its recipient's point of view. */
    private record Copy(String headline, String sentence) {
    }

    private NotificationEmailCopy() {
    }

    /** Whether this type may produce an {@code EMAIL}-channel row at all. */
    public static boolean isEmailable(NotificationMessageType messageType) {
        return copy(messageType) != null;
    }

    /**
     * Renders the email for one notification row.
     *
     * @throws IllegalArgumentException if the row's type is not on the allowlist — callers must
     *                                  check {@link #isEmailable} first, and a caller that does
     *                                  not is a bug that must be loud rather than a customer
     *                                  receiving something this class has no words for
     */
    public static EmailMessage render(Notification notification) {
        Copy copy = copy(notification.getMessageType());
        if (copy == null) {
            throw new IllegalArgumentException(
                    "No customer-facing email copy exists for " + notification.getMessageType()
                            + "; it is not on the email allowlist.");
        }
        return new EmailMessage("Pronto — " + copy.headline(),
                body(copy, notification.getRelatedOrderId()));
    }

    private static String body(Copy copy, Long relatedOrderId) {
        String reference = relatedOrderId == null ? "" : "מספר הזמנה: " + relatedOrderId + "\n\n";
        return """
                שלום,

                %s

                %sאפשר לראות את הפרטים המלאים באפליקציה של Pronto.

                Pronto
                """.formatted(copy.sentence(), reference);
    }

    /**
     * The allowlist. {@code null} means "never emailed" — see this class's Javadoc for why the
     * three {@code null} cases are what they are.
     *
     * <p>Exhaustive with no {@code default}, so adding a {@link NotificationMessageType} is a
     * compile error here rather than a silent new customer email. The wording mirrors
     * {@code frontend/src/features/notifications/notificationLabels.ts}, including its two
     * load-bearing rules: availability is never described as selection, and every sentence is
     * written from the recipient's point of view, not an observer's.
     */
    private static Copy copy(NotificationMessageType messageType) {
        return switch (messageType) {
            // ---- Standard order flow ----
            case ORDER_CREATED -> new Copy("התקבלה בקשת הזמנה חדשה",
                    "לקוח שלח אליך בקשת הזמנה חדשה וממתין לתשובה.");
            case ORDER_CONFIRMED -> new Copy("ההזמנה שלך אושרה",
                    "בעל המקצוע אישר את ההזמנה שלך.");
            case ORDER_REJECTED -> new Copy("הבקשה שלך נדחתה",
                    "בעל המקצוע לא יכול לקחת את ההזמנה הזו. אפשר לבחור בעל מקצוע אחר.");
            case ORDER_CANCELLED -> new Copy("ההזמנה בוטלה",
                    "ההזמנה בוטלה ולא תתקיים.");
            case ORDER_ON_THE_WAY -> new Copy("בעל המקצוע בדרך אליך",
                    "בעל המקצוע יצא לדרך ובקרוב יגיע לכתובת שמסרת.");
            case ORDER_ARRIVED -> new Copy("בעל המקצוע הגיע לכתובת שלך",
                    "בעל המקצוע הגיע לכתובת שמסרת.");
            case ORDER_COMPLETED -> new Copy("העבודה הושלמה",
                    "בעל המקצוע סימן שהעבודה הושלמה.");
            case ORDER_EXPIRED -> new Copy("הבקשה פגה תוקף",
                    "הבקשה לא נענתה בזמן ולכן פג תוקפה. אפשר לשלוח בקשה חדשה.");

            // ---- Pronto SOS, professional-facing ----
            case SOS_OFFER_RECEIVED -> new Copy("קריאת SOS חדשה",
                    "התקבלה קריאת SOS שמתאימה לך. הזמן לתגובה מוגבל.");
            case SOS_OFFER_EXPIRED -> new Copy("הזמן להגיב לקריאת ה-SOS הסתיים",
                    "חלון הזמן לתגובה לקריאת ה-SOS הסתיים.");
            // The award, and the one message here that must not be readable as anything less.
            case SOS_PROFESSIONAL_SELECTED -> new Copy("הלקוח בחר בך",
                    "הלקוח בחר בך לקריאת ה-SOS. נא לאשר את ההזמנה.");
            case SOS_NOT_SELECTED -> new Copy("הלקוח בחר בעל מקצוע אחר",
                    "הפעם הלקוח בחר בעל מקצוע אחר לקריאת ה-SOS.");

            // ---- Pronto SOS, customer-facing ----
            case SOS_CANDIDATES_READY -> new Copy("יש בעלי מקצוע זמינים",
                    "נמצאו בעלי מקצוע זמינים לקריאה שלך ואפשר לבחור אחד מהם.");
            case SOS_PROFESSIONAL_CONFIRMED -> new Copy("בעל המקצוע אישר את ההזמנה",
                    "בעל המקצוע שבחרת אישר את ההזמנה.");
            case SOS_ON_THE_WAY -> new Copy("בעל המקצוע יצא לדרך",
                    "בעל המקצוע יצא לדרך אליך.");
            case SOS_ARRIVED -> new Copy("בעל המקצוע הגיע",
                    "בעל המקצוע הגיע לכתובת שמסרת.");
            case SOS_COMPLETED -> new Copy("הטיפול הושלם",
                    "בעל המקצוע סימן שהטיפול הושלם.");
            case SOS_CANCELLED -> new Copy("קריאת ה-SOS בוטלה",
                    "קריאת ה-SOS בוטלה.");
            // Minutes after the fact, by which time the customer may well have walked away from
            // the screen -- unlike the two suppressed failures below, which land while they are
            // still looking at it.
            case SOS_EXPIRED -> new Copy("קריאת ה-SOS הסתיימה",
                    "קריאת ה-SOS הסתיימה בלי שנבחר בעל מקצוע. אפשר לשלוח קריאה חדשה.");

            // ---- Never emailed ----
            case SOS_NO_PROFESSIONALS, SOS_TEMPORARILY_UNAVAILABLE, EMAIL_VERIFICATION -> null;
        };
    }
}
