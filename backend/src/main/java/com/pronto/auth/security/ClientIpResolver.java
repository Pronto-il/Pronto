package com.pronto.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * "Which client sent this request?" — answered correctly whether or not there is a load balancer in
 * front of the application.
 *
 * <p><b>The problem this solves.</b> Rate limiting keyed on
 * {@link HttpServletRequest#getRemoteAddr()} is correct only while clients connect directly. Behind
 * an AWS ALB every request arrives from the load balancer's own private address, so every user in
 * the world shares one counter: the register limiter (10 requests / 10 minutes) would let the first
 * ten registrations of each window through and refuse everybody else. That is not a weaker limiter,
 * it is a self-inflicted denial of service.
 *
 * <p><b>Why the obvious fix is a vulnerability.</b> Simply reading {@code X-Forwarded-For} is worse
 * than not fixing it. That header is client-supplied; with no proxy in front to overwrite it,
 * anyone can send a different value on every request and never be limited at all, or send a
 * victim's address and get them limited instead.
 *
 * <p><b>The rule implemented here.</b> The forwarded chain is consulted only when the peer that
 * actually opened the TCP connection ({@code getRemoteAddr()}) is inside a configured trusted
 * network, and then the chain is walked from the right, skipping trusted hops, to find the
 * left-most address that was not added by our own infrastructure. With
 * {@code pronto.security.trusted-proxies} empty — the default, and what every local and CI run uses
 * — this class returns {@code getRemoteAddr()} and behaves exactly as before, so trusting a proxy
 * is something a deployment opts into by naming its network, never something that happens by
 * accident.
 *
 * <p>Configured as CIDR blocks, e.g. {@code TRUSTED_PROXIES=10.0.0.0/16,172.31.0.0/16} for an ALB
 * in a VPC. Matching is done on raw address bytes, so IPv4 and IPv6 both work without a dependency.
 */
@Component
public class ClientIpResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);
    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private final List<CidrBlock> trustedProxies;

    public ClientIpResolver(@Value("${pronto.security.trusted-proxies:}") String trustedProxies) {
        this.trustedProxies = parse(trustedProxies);
        if (this.trustedProxies.isEmpty()) {
            log.info("Client IP resolution: direct peer address only. "
                    + "X-Forwarded-For is ignored (pronto.security.trusted-proxies is empty).");
        } else {
            log.info("Client IP resolution: X-Forwarded-For honoured for peers within {}.",
                    this.trustedProxies);
        }
    }

    /**
     * @return the address to attribute this request to. Never {@code null}; falls back to the peer
     *         address for anything it cannot make sense of, which is the fail-closed direction —
     *         a request that cannot be attributed to a real client is attributed to the hop we can
     *         actually see rather than being exempted from limiting.
     */
    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (trustedProxies.isEmpty() || peer == null || !isTrusted(peer)) {
            return peer == null ? "unknown" : peer;
        }

        // EVERY instance of the header, not just the first. RFC 7230 says repeated field lines are
        // semantically one comma-joined value, but `getHeader` returns only the first — so a client
        // that sends its own X-Forwarded-For as a separate header line could, depending on how the
        // load balancer merges them, have that line read instead of the one the balancer appended
        // the real client IP to. Joining every instance in order removes the question entirely.
        String header = joinHeaders(request.getHeaders(FORWARDED_FOR));
        if (header.isBlank()) {
            return peer;
        }

        // X-Forwarded-For: client, proxy1, proxy2 — appended left to right, so the right-most entry
        // is the hop closest to us. Walking right to left and stopping at the first address that is
        // not one of our own proxies yields the earliest address we can still vouch for. Anything
        // further left was written by a client and is unverifiable.
        String[] hops = header.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (hop.isEmpty()) {
                continue;
            }
            if (!isTrusted(hop)) {
                return hop;
            }
        }
        return peer;
    }

    /**
     * Flattens every {@code X-Forwarded-For} header line into one chain, preserving order. A null
     * enumeration (no such header) yields an empty string.
     */
    private static String joinHeaders(java.util.Enumeration<String> values) {
        if (values == null) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        while (values.hasMoreElements()) {
            String value = values.nextElement();
            if (value == null || value.isBlank()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append(value);
        }
        return joined.toString();
    }

    private boolean isTrusted(String address) {
        byte[] bytes = toBytes(address);
        if (bytes == null) {
            return false;
        }
        return trustedProxies.stream().anyMatch(block -> block.contains(bytes));
    }

    /**
     * IPv4/IPv6 literal → raw bytes, or {@code null}.
     *
     * <p>The {@link #isIpLiteral} pre-check is not cosmetic. {@code InetAddress.getByName} resolves
     * anything that is not a literal through DNS, and this method is fed {@code X-Forwarded-For}
     * values — so without the guard, a request header could make this server issue an arbitrary DNS
     * lookup on the request thread, which is both an outbound-traffic primitive for an attacker and
     * an easy way to stall the connection pool. ({@code InetAddress.ofLiteral} would say this
     * directly, but it is Java 22+ and this project targets 21.)
     */
    private static byte[] toBytes(String address) {
        if (!isIpLiteral(address)) {
            return null;
        }
        try {
            return InetAddress.getByName(address).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** True if every character could belong to a numeric IPv4 or IPv6 literal. */
    private static boolean isIpLiteral(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        boolean sawColon = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ':') {
                sawColon = true;
            } else if (c == '.') {
                continue;
            } else if (!(c >= '0' && c <= '9')
                    && !(sawColon && ((c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')))) {
                return false;
            }
        }
        return true;
    }

    private static List<CidrBlock> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<CidrBlock> blocks = new ArrayList<>();
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                blocks.add(CidrBlock.parse(trimmed));
            } catch (IllegalArgumentException | UnknownHostException e) {
                throw new IllegalStateException(
                        "pronto.security.trusted-proxies contains an unparseable CIDR block: '" + trimmed
                                + "'. Expected e.g. 10.0.0.0/16.", e);
            }
        }
        return List.copyOf(blocks);
    }

    /** A CIDR block, compared on raw address bytes so one implementation covers IPv4 and IPv6. */
    private record CidrBlock(byte[] network, int prefixBits, String display) {

        static CidrBlock parse(String cidr) throws UnknownHostException {
            int slash = cidr.indexOf('/');
            String host = slash < 0 ? cidr : cidr.substring(0, slash);
            byte[] network = toBytes(host);
            if (network == null) {
                throw new IllegalArgumentException("not an IP literal: " + host);
            }
            int prefix = slash < 0 ? network.length * 8 : Integer.parseInt(cidr.substring(slash + 1));
            if (prefix < 0 || prefix > network.length * 8) {
                throw new IllegalArgumentException("prefix length out of range: " + prefix);
            }
            return new CidrBlock(network, prefix, cidr);
        }

        boolean contains(byte[] address) {
            // An IPv4 address is never inside an IPv6 block, and vice versa: comparing them would
            // need a mapping convention, and getting that wrong in a trust decision is expensive.
            if (address.length != network.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixBits % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }

        @Override
        public String toString() {
            return display;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof CidrBlock block && prefixBits == block.prefixBits
                    && Arrays.equals(network, block.network);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(network) * 31 + prefixBits;
        }
    }
}
