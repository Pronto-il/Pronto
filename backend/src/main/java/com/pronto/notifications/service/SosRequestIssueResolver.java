package com.pronto.notifications.service;

import java.util.Collection;
import java.util.Map;

/**
 * The one fact this package needs about an SOS request: <b>which issue it belongs to</b>.
 *
 * <h2>Why an interface here rather than a repository call there</h2>
 *
 * A customer's SOS notification carries {@code related_sos_request_id}, which is the right
 * subject to store — the row is about an SOS attempt, and the column is FK-constrained to
 * {@code sos_requests}. But the customer's live SOS screen is reached as
 * {@code /issues/{issueId}/sos-booking}: it is anchored to the <em>problem</em>, not to the
 * attempt, deliberately, because one issue accumulates many attempts and the customer should land
 * on their problem's current state rather than on whichever attempt happened to notify them. So
 * the notification had the wrong id to navigate with, and every customer-facing SOS row in the
 * bell was a dead end.
 *
 * <p>The missing piece is a single {@code sos_requests.issue_id} lookup — but {@code sos} already
 * depends on {@code notifications} (it writes notifications on every transition), so importing
 * {@code SosRequestRepository} here would close a package cycle. Inverting the dependency costs
 * one interface: {@code notifications} declares what it needs, {@code sos} implements it, and the
 * arrow keeps pointing the way it already did. "Nothing depends on {@code sos}" stays true.
 *
 * <p>Deliberately <b>not</b> a new {@code related_issue_id} column on {@code notifications}. That
 * would copy a fact that already has an owner into a second table, where it could go stale, and
 * would mean threading an issue id through every one of the a dozen call sites that record an SOS
 * notification. The relationship is immutable and one indexed read away; deriving it at read time
 * is both cheaper to maintain and impossible to get out of step.
 */
public interface SosRequestIssueResolver {

    /**
     * {@code sosRequestId -> issueId}, for every id that resolves.
     *
     * <p>Batched rather than one lookup per row: the notification feed is unpaginated, and a
     * customer with a few SOS attempts behind them would otherwise turn one feed read into N
     * queries. Ids that do not resolve are simply absent from the map — the caller renders those
     * rows without a deep link rather than failing the whole feed.
     */
    Map<Long, Long> issueIdsBySosRequestId(Collection<Long> sosRequestIds);
}
