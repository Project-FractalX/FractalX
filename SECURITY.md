# Security Policy

## Supported Versions

Only the latest stable release of FractalX receives security updates.

| Version | Supported          |
|---------|--------------------|
| 0.4.x   | :white_check_mark: |
| < 0.4.0 | :x:                |

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

If you discover a security vulnerability in FractalX, report it privately via one of the following channels:

- **GitHub Private Vulnerability Reporting**: Use the [Security Advisories](https://github.com/fractalx-org/FractalX/security/advisories/new) page to submit a report confidentially.
- **Email**: Contact the maintainer directly at the email address listed on [@sathninduk](https://github.com/sathninduk)'s GitHub profile.

Please include as much of the following as possible:

- A description of the vulnerability and its potential impact
- The affected version(s)
- Steps to reproduce or proof-of-concept
- Any suggested mitigation or fix

You can expect an initial response within **72 hours** and a resolution or mitigation plan within **14 days** for confirmed vulnerabilities.

## Scope

Security reports are accepted for the following components:

- `fractalx-core` — static analysis and code generation engine
- `fractalx-maven-plugin` — Maven plugin mojos
- `fractalx-runtime` — runtime library bundled into generated services
- `fractalx-annotations` — compile-time annotations
- `fractalx-initializr-core` — project initialisation core

### Out of Scope

- Vulnerabilities in **generated output code** that arise from insecure patterns in the user's own monolith source (FractalX mirrors what it finds)
- Third-party dependencies — please report those upstream
- Issues in sample/demo projects

## Security Considerations for Generated Services

FractalX generates microservice source code that ships with opinionated defaults. Before deploying generated services to production, review the following:

- **Auth service**: The generated `AuthService` uses JWT with a default JWKS URI placeholder — replace with your Identity Provider before deployment.
- **Internal tokens**: Inter-service calls are authenticated with short-lived internal tokens minted by the gateway — ensure `INTERNAL_TOKEN_SECRET` is set to a strong secret in production.
- **CORS**: Default allowed origins include `localhost` — update `fractalx.corsAllowedOrigins` in `fractalx-config.yml` for production.
- **Actuator endpoints**: Generated services expose `/actuator/health` and `/actuator/metrics` — restrict access at the gateway or firewall level.
- **Secrets in config**: Never commit `fractalx-config.yml` containing real credentials — use environment variable substitution or a secrets manager.

## Disclosure Policy

We follow a **coordinated disclosure** model. Once a fix is available, we will:

1. Release a patched version
2. Publish a [GitHub Security Advisory](https://github.com/fractalx-org/FractalX/security/advisories) with full details
3. Credit the reporter (unless they prefer to remain anonymous)
