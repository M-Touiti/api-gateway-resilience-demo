# ADR-0005: Validate JWTs at the gateway and pass identity in headers

- **Status:** Accepted, for a trusted network only
- **Decided:** 2026-05-14 (recorded 2026-10-31)

## Context

Every protected service needs to know who is calling. If each service validated tokens itself, each would need the signing key, the JWT library and the same logic, kept in step across languages and teams.

The rate limiter also needs the caller's identity before a request goes downstream, so the gateway has to read the token in any case.

## Decision

Validate tokens **once, at the gateway**, in a custom `JwtAuthenticationGatewayFilterFactory` that uses JJWT with an HS256 shared secret. It doesn't use Spring Security.

On success, the filter:

1. Sets `X-User-Email` and `X-User-Role` from the token's claims, **overwriting any values the client sent**.
2. Removes the `Authorization` header, so the token doesn't travel further than it needs to.
3. Stores the email as an exchange attribute for the rate limiter's key resolver.

On failure, it returns 401 before any Redis or downstream call is made. Downstream services read the headers and do no token validation. Authorization rules, such as the admin check on `GET /users`, stay in those services.

## Consequences

**Benefits**

- Only the gateway holds the signing key, and downstream services need no security library.
- Invalid requests are rejected at the edge without using downstream or Redis capacity.
- Validation is stateless and fast. *Measured:* the whole gateway adds about 0.4 ms at the median at 500 req/s, including this filter. [Results](../../performance/README.md#gateway-overhead)

**Costs and risks**

- **Downstream services trust the network.** Anything that can reach them directly can send any `X-User-Role` value and act as an admin. This is only safe if they aren't reachable except through the gateway. The demo publishes ports 8081 and 8082 for convenience, and a production deployment must not. Mutual TLS or a signed internal token would remove this dependency on the network.
- **Tokens can't be revoked.** A stolen token works until it expires, because nothing is checked beyond the signature and expiry. Short lifetimes or a Redis denylist would help.
- **The HS256 secret can both verify and sign**, so it can't be shared with other verifiers. RS256 or ES256 with a JWKS endpoint would allow that, and would support key rotation.
- `application.yml` has a default development secret. A deployment that forgets to set `JWT_SECRET` runs with a publicly known key, so production should fail at startup instead.
- Issuer and audience aren't checked, so any token signed with the key is accepted.
- `POST /auth/login` returns a placeholder rather than a real JWT, so test tokens are generated separately. A real deployment needs an identity provider.
