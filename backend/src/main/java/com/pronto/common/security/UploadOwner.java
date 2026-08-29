package com.pronto.common.security;

/**
 * Who an uploaded image belongs to — the one ownership context the shared upload/presign flow
 * is parameterised by.
 *
 * <p><b>Why this exists.</b> Before deferred authentication, "the owner of an image" and "the
 * authenticated user" were the same thing, so {@code storage} resolved ownership from a bare
 * {@code Long callerId}. A guest describing a fault has no account and therefore no such id,
 * but still has to be able to attach photos — the alternative being a signup form standing
 * between a person and a picture of their leaking pipe. This record is the smallest thing that
 * lets one upload flow serve both: the validation, the size cap, the content-type allow-list,
 * the key template, the presigned-URL TTL and the ownership check are all unchanged and shared;
 * only <em>whose namespace the key lands in</em> varies.
 *
 * <p><b>Both fields can be set at once, and that is the point.</b> A guest who uploads photos and
 * then registers mid-flow is, for the rest of that journey, simultaneously a customer (their new
 * account) and the guest who owns those already-uploaded keys. Collapsing that to "the account
 * wins" would make their own photos unreadable to them the moment they did what we asked. So an
 * owner may hold both identities, and a key is theirs if it matches <em>either</em>. New uploads
 * still prefer the account namespace ({@link #preferredKeyOwnerSegment()}), because that is the
 * ownership that outlives the session.
 *
 * <p><b>{@code guestId} is never client-asserted.</b> It is only ever read out of a signed,
 * unexpired guest-session token minted by this backend
 * ({@code auth.security.GuestSessionTokenService}), exactly as {@code customerId} is only ever
 * read out of a verified JWT. A caller naming a guest id in a request body or a storage key
 * proves nothing — see {@code storage.ImageKeyUtils}.
 *
 * <p>Lives in {@code common.security} beside {@link AuthenticatedUser}, for the same reason that
 * one does: it is a principal type consumed by several domain packages ({@code storage},
 * {@code issues}) and produced by {@code auth}, and it depends on no domain package itself.
 */
public record UploadOwner(Long customerId, String guestId) {

    /** An authenticated customer, the only kind of owner that existed before guest uploads. */
    public static UploadOwner customer(Long customerId) {
        return new UploadOwner(customerId, null);
    }

    /** A visitor holding a valid guest-session token and no account. */
    public static UploadOwner guest(String guestId) {
        return new UploadOwner(null, guestId);
    }

    /** True when neither identity was established — the caller is anonymous and may do nothing. */
    public boolean isAnonymous() {
        return customerId == null && guestId == null;
    }

    public boolean isCustomer() {
        return customerId != null;
    }

    public boolean isGuest() {
        return guestId != null;
    }

    /**
     * The identity a <em>new</em> upload's key should be namespaced under. The account wins when
     * both are present: a {@code customers/{id}/...} key stays owned for the life of the account,
     * whereas a {@code guests/{uuid}/...} key is only provable for as long as the session token
     * lives. There is no reason to mint a shorter-lived ownership than the caller already has.
     */
    public String preferredKeyOwnerSegment() {
        return customerId != null ? String.valueOf(customerId) : guestId;
    }
}
