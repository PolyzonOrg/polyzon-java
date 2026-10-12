# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - 2026-10-11

### Added

- `PolyzonClient` for the Polyzon public API: `search`, `searchAll` (lazy pagination),
  `getModel`, `getModels` (bounded-parallel batch), `me`, `limits`, and `CompletableFuture`
  twins `searchAsync`, `getModelAsync`, `meAsync`, `limitsAsync`.
- Immutable result records with response metadata (request id, daily rate limit).
- Exception hierarchy under `org.polyzon.errors` mapped from HTTP status and problem codes.
- Automatic retries with `Retry-After` support (capped at 60 seconds) and jittered backoff.
- Explicit HTTP proxy support with Basic proxy authentication, custom `ProxySelector` and
  custom `HttpClient` escape hatch.
- Security defaults: token validation and masking, https-only base URLs (http for loopback),
  no redirects, 8 MiB response cap, strict JSON parser with a nesting limit.
- JPMS module `org.polyzon`; no dependencies outside the JDK; Java 17+.

[Unreleased]: https://github.com/PolyzonOrg/polyzon-java/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/PolyzonOrg/polyzon-java/releases/tag/v1.0.0
