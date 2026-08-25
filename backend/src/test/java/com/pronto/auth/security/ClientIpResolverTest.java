package com.pronto.auth.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Trusted-proxy client-IP resolution — the control that decides who a rate-limit counter belongs to.
 *
 * <p>Two failure modes are being guarded against, and they pull in opposite directions: trusting
 * {@code X-Forwarded-For} from anyone lets a client evade limiting entirely or frame another
 * client, while trusting nobody collapses every user onto the load balancer's address once this is
 * deployed behind an ALB. The tests below assert both halves.
 */
class ClientIpResolverTest {

    private static MockHttpServletRequest request(String peer, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    /** A request carrying several separate {@code X-Forwarded-For} header lines. */
    private static MockHttpServletRequest requestWithHeaders(String peer, String... forwardedForLines) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        for (String line : forwardedForLines) {
            request.addHeader("X-Forwarded-For", line);
        }
        return request;
    }

    // ---- no trusted proxies configured (the default, and every local/CI run) ----

    @Test
    void withNoTrustedProxies_theHeaderIsIgnoredEntirely() {
        ClientIpResolver resolver = new ClientIpResolver("");

        assertThat(resolver.resolve(request("203.0.113.7", "1.2.3.4"))).isEqualTo("203.0.113.7");
    }

    @Test
    void withNoTrustedProxies_aSpoofedHeaderCannotChangeTheAttribution() {
        ClientIpResolver resolver = new ClientIpResolver("");
        // An attacker rotating this header on every request would otherwise get an unlimited number
        // of fresh rate-limit buckets.
        String first = resolver.resolve(request("203.0.113.7", "9.9.9.1"));
        String second = resolver.resolve(request("203.0.113.7", "9.9.9.2"));

        assertThat(first).isEqualTo(second).isEqualTo("203.0.113.7");
    }

    // ---- behind a configured trusted proxy ----

    @Test
    void fromATrustedProxy_theForwardedClientIsUsed() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/16");

        assertThat(resolver.resolve(request("10.0.4.9", "203.0.113.7"))).isEqualTo("203.0.113.7");
    }

    @Test
    void fromAnUntrustedPeer_theForwardedHeaderIsStillIgnored() {
        // The whole rule in one test: the header is only as trustworthy as the hop that delivered it.
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/16");

        assertThat(resolver.resolve(request("198.51.100.5", "203.0.113.7"))).isEqualTo("198.51.100.5");
    }

    @Test
    void aChainIsWalkedFromTheRight_stoppingAtTheFirstAddressWeDidNotAdd() {
        // X-Forwarded-For: <client>, <our-proxy-1>, <our-proxy-2>. Everything to the left of the
        // first non-proxy entry was written by a client and is unverifiable.
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/16");

        assertThat(resolver.resolve(request("10.0.4.9", "203.0.113.7, 10.0.1.1, 10.0.2.2")))
                .isEqualTo("203.0.113.7");
    }

    @Test
    void aClientForgedPrefixIsDiscarded_notTrusted() {
        // The client sent "1.2.3.4" itself; the ALB appended the real source. Taking the left-most
        // entry — the common mistake — would let anyone claim any address.
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/16");

        assertThat(resolver.resolve(request("10.0.4.9", "1.2.3.4, 203.0.113.7")))
                .isEqualTo("203.0.113.7");
    }

    @Test
    void anEmptyOrWhitespaceOnlyHeaderFallsBackToThePeer() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/16");

        assertThat(resolver.resolve(request("10.0.4.9", "   "))).isEqualTo("10.0.4.9");
        assertThat(resolver.resolve(request("10.0.4.9", null))).isEqualTo("10.0.4.9");
    }

    @Test
    void aChainOfNothingButTrustedProxiesFallsBackToThePeer() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/16");

        assertThat(resolver.resolve(request("10.0.4.9", "10.0.1.1, 10.0.2.2"))).isEqualTo("10.0.4.9");
    }

    @Test
    void aHostnameInTheHeaderIsNotResolved_soNoDnsLookupCanBeTriggered() {
        // Feeding a hostname to InetAddress.getByName would make this server issue a DNS query on
        // the request thread, on demand, for an attacker-chosen name.
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/16");

        assertThat(resolver.resolve(request("10.0.4.9", "evil.example.com"))).isEqualTo("evil.example.com");
    }

    // ---- CIDR handling ----

    @Test
    void multipleBlocksAreHonoured() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/16, 172.31.0.0/16");

        assertThat(resolver.resolve(request("172.31.9.9", "203.0.113.7"))).isEqualTo("203.0.113.7");
        assertThat(resolver.resolve(request("192.168.1.1", "203.0.113.7"))).isEqualTo("192.168.1.1");
    }

    @Test
    void aBareAddressWithNoPrefixMeansExactlyThatHost() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.4.9");

        assertThat(resolver.resolve(request("10.0.4.9", "203.0.113.7"))).isEqualTo("203.0.113.7");
        assertThat(resolver.resolve(request("10.0.4.10", "203.0.113.7"))).isEqualTo("10.0.4.10");
    }

    @Test
    void nonByteAlignedPrefixesAreHandled() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/12");

        assertThat(resolver.resolve(request("10.15.255.1", "203.0.113.7"))).isEqualTo("203.0.113.7");
        assertThat(resolver.resolve(request("10.16.0.1", "203.0.113.7"))).isEqualTo("10.16.0.1");
    }

    @Test
    void ipv6BlocksWork_andDoNotMatchIpv4() {
        ClientIpResolver resolver = new ClientIpResolver("2001:db8::/32");

        assertThat(resolver.resolve(request("2001:db8::1", "203.0.113.7"))).isEqualTo("203.0.113.7");
        assertThat(resolver.resolve(request("10.0.4.9", "203.0.113.7"))).isEqualTo("10.0.4.9");
    }

    @Test
    void anUnparseableCidrFailsAtStartupRatherThanSilentlyTrustingNothing() {
        // A typo in TRUSTED_PROXIES must not degrade quietly into "no proxy is trusted", because
        // that failure mode is invisible until the platform is rate-limiting all its users as one.
        assertThatThrownBy(() -> new ClientIpResolver("10.0.0.0/notanumber"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trusted-proxies");
    }

    // ---- multiple header instances (MS1 pre-DONE audit) ----

    @Test
    void severalForwardedForHeaderLinesAreTreatedAsOneChain() {
        // RFC 7230: repeated field lines are semantically one comma-joined value. `getHeader` returns
        // only the first, so reading it alone would have let a client's own header line be used
        // instead of the one the load balancer appended the real client IP to.
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/16");

        assertThat(resolver.resolve(requestWithHeaders("10.0.4.9", "203.0.113.7", "10.0.1.1")))
                .isEqualTo("203.0.113.7");
    }

    @Test
    void aClientForgedHeaderLineCannotWinByBeingFirst() {
        // The attacker sends its own X-Forwarded-For as a separate line; the balancer appends the
        // real source on another. Walking the COMBINED chain from the right still lands on the real
        // client, and the forged line is discarded like any other unverifiable left-hand entry.
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/16");

        assertThat(resolver.resolve(requestWithHeaders("10.0.4.9", "8.8.8.8", "203.0.113.7")))
                .isEqualTo("203.0.113.7");
    }

    @Test
    void multipleHeaderLinesFromAnUntrustedPeerAreStillIgnoredEntirely() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/16");

        assertThat(resolver.resolve(requestWithHeaders("198.51.100.5", "8.8.8.8", "203.0.113.7")))
                .isEqualTo("198.51.100.5");
    }

    @Test
    void aMixOfCommaListsAcrossSeveralLinesIsFlattenedInOrder() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/16");

        assertThat(resolver.resolve(requestWithHeaders("10.0.4.9",
                "8.8.8.8, 1.1.1.1", "203.0.113.7, 10.0.1.1, 10.0.2.2")))
                .isEqualTo("203.0.113.7");
    }

    @Test
    void blankHeaderLinesAreSkippedRatherThanBreakingTheChain() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/16");

        assertThat(resolver.resolve(requestWithHeaders("10.0.4.9", "", "203.0.113.7", "   ")))
                .isEqualTo("203.0.113.7");
    }

    @Test
    void malformedEntriesInAMultiLineChainStillFailClosed() {
        // "not-an-ip" is not a trusted proxy, so the walk stops there rather than skipping past it
        // into attacker-controlled territory.
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/16");

        assertThat(resolver.resolve(requestWithHeaders("10.0.4.9", "8.8.8.8", "not-an-ip")))
                .isEqualTo("not-an-ip");
    }

    @Test
    void aNullPeerDoesNotBlowUp() {
        assertThat(new ClientIpResolver("").resolve(request(null, null))).isEqualTo("unknown");
    }
}
