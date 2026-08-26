package com.pronto.auth.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * An IPv4 or IPv6 CIDR block, compared on raw address bytes so one implementation covers both
 * families without an external dependency.
 *
 * <p><b>Why this is a top-level class.</b> It began as a private record inside
 * {@link ClientIpResolver}, which was the right size while exactly one class needed it. Production
 * MS4 added a second consumer — {@code auth.config.ProductionHardeningStartupGuard} validates that
 * the configured {@code TRUSTED_PROXIES} blocks lie inside private address space — and the two must
 * agree byte for byte on what a CIDR string means. A startup guard that parsed
 * {@code TRUSTED_PROXIES} even slightly differently from the resolver that later acts on it would
 * be worse than no guard: it would approve a configuration whose real behaviour it had never
 * examined. So there is one parser, used by both.
 *
 * <p>Mixed families never match: an IPv4 address is not inside an IPv6 block and vice versa.
 * Comparing them would need a mapping convention, and getting that wrong inside a trust decision is
 * expensive.
 */
public record CidrBlock(byte[] network, int prefixBits, String display) {

    /**
     * @param cidr {@code address/prefix}, or a bare address (treated as a single-host block)
     * @throws IllegalArgumentException if the address is not an IP literal, or the prefix is out of
     *                                  range for its family
     */
    public static CidrBlock parse(String cidr) throws UnknownHostException {
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

    /** True if {@code address} (raw bytes, same family) falls inside this block. */
    public boolean contains(byte[] address) {
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

    /**
     * True if every address in {@code other} is also in this block — i.e. {@code other} is this
     * block or a subnet of it. A shorter prefix can never be contained in a longer one, which is
     * the check that makes {@code 0.0.0.0/0} fail against every private range.
     */
    public boolean containsBlock(CidrBlock other) {
        return other.prefixBits >= this.prefixBits && contains(other.network);
    }

    /** IPv4/IPv6 literal → raw bytes, or {@code null} if it is not a literal. */
    public static byte[] toBytes(String address) {
        if (!isIpLiteral(address)) {
            return null;
        }
        try {
            return InetAddress.getByName(address).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * True if every character could belong to a numeric IPv4 or IPv6 literal.
     *
     * <p>Not cosmetic. {@link InetAddress#getByName} resolves anything that is not a literal
     * through DNS, and {@link ClientIpResolver} feeds this {@code X-Forwarded-For} values — so
     * without the guard, a request header could make the server issue an arbitrary DNS lookup on
     * the request thread, which is both an outbound-traffic primitive for an attacker and an easy
     * way to stall the connection pool. ({@code InetAddress.ofLiteral} would say this directly, but
     * it is Java 22+ and this project targets 21.)
     */
    public static boolean isIpLiteral(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        // Whether hex letters are legal is decided by scanning the WHOLE string for a colon first,
        // rather than by whether one has been seen so far. The left-to-right version rejected every
        // IPv6 address beginning with a hex letter — `fc00::/7`, `fe80::1` — which is how the
        // private-range table in ProductionHardeningStartupGuard first failed to build.
        boolean hasColon = value.indexOf(':') >= 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ':' || c == '.') {
                continue;
            }
            boolean digit = c >= '0' && c <= '9';
            boolean hexLetter = (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!digit && !(hasColon && hexLetter)) {
                return false;
            }
        }
        return true;
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
