# 🔒 Keycloak Custom Authenticator With Three Fields

A custom **Keycloak Authentication SPI** provider that extends the standard username/password login step to also accept a **Government ID (customer/national identifier)** as a login identifier, in addition to the default username or email.

> Built on top of Keycloak's `AbstractUsernameFormAuthenticator`, this provider is a drop-in replacement for the default *Username Password Form* execution in any authentication flow.

---

## Table of Contents

- [Overview](#overview)
- [How It Works](#how-it-works)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Prerequisites](#prerequisites)
- [Build](#build)
- [Deployment to Keycloak](#deployment-to-keycloak)
- [Configuration](#configuration)
    - [1. Add the `governmentId` User Attribute](#1-add-the-governmentid-user-attribute)
    - [2. Configure the Authentication Flow](#2-configure-the-authentication-flow)
- [Docker Build](#docker-build)
- [Logging](#logging)
- [Contact](#contact)
- [License](#license)

---

## Overview

Keycloak's default login form only resolves a user by **username** or **email**. This module introduces a new `Authenticator` implementation that is registered via Keycloak's Service Provider Interface (SPI) and lets end users authenticate using **three possible fields**:

| Field         | Purpose                                                             |
|---------------|----------------------------------------------------------------------|
| `username`    | Standard Keycloak username                                          |
| `password`    | Standard Keycloak password                                          |
| `governmentId`| Custom user attribute (e.g. national ID / customer ID) used as an alternate login identifier |

The login form itself still exposes a single identifier input (matching Keycloak's default `login-username.ftl` template) and a password input — the provider is what makes that single identifier input **smart**: it automatically detects whether the value entered is a username/email or a numeric government ID, and resolves the corresponding user accordingly.

## How It Works

The core logic lives in [`CustomAuthenticatorForm`](src/main/java), registered through [`CustomAuthenticatorFormFactory`](src/main/java) and exposed to Keycloak via the standard SPI descriptor file:

```
META-INF/services/org.keycloak.authentication.AuthenticatorFactory
```

On every login attempt:

1. **`authenticate()`** — renders the standard username/password challenge form (honors `login_hint` and "remember me" cookies, same as Keycloak's built-in form).
2. **`action()`** — reads the submitted form data and delegates to `validateUserAndPassword()`.
3. **User resolution (`getCustomUserFromForm`)** — the identifier submitted in the `username` field is inspected:
    - If the value is **purely numeric** (`isGovernmentId()` → matches `\d+`), the user is looked up by the **`governmentId`** custom user attribute via `searchForUserByUserAttributeStream`.
    - Otherwise, the user is resolved the standard way, by **username or email**.
4. **Password validation** — once the user is resolved, the standard Keycloak password validation (`validatePassword`) and brute-force / account-enabled checks (`validateUser`, `enabledUser`) are applied exactly as in the default flow.
5. On success, `context.success()` is called and the flow proceeds to the next authentication step (OTP, consent, etc., depending on how the flow is configured).

This means **no changes are required in the login theme/template** — the smart resolution happens entirely on the backend, based on the shape of the value submitted in the identifier field.

## Tech Stack

| Component        | Version / Detail                     |
|-------------------|---------------------------------------|
| Java              | 17                                     |
| Keycloak SPI      | `keycloak-server-spi`, `keycloak-server-spi-private`, `keycloak-services` — 25.0.1 |
| Build tool        | Apache Maven                          |
| Boilerplate       | Lombok, Google `auto-service` (for SPI annotation processing) |
| Packaging         | `jar`, deployed as a Keycloak provider |
| License           | Apache License 2.0                    |

## Project Structure

```
keycloak-login-customer-authenticator/
├── src/main/java/com/hrouz/tuto/keycloak/auth/
│   ├── CustomAuthenticatorConstants.java     # Form field name constants (username, password, governmentId)
│   ├── CustomAuthenticatorForm.java          # Core Authenticator implementation (authenticate/action/validate)
│   └── CustomAuthenticatorFormFactory.java   # AuthenticatorFactory — registers the provider with Keycloak
├── src/main/resources/META-INF/services/
│   └── org.keycloak.authentication.AuthenticatorFactory   # SPI descriptor (points to the Factory class)
├── Dockerfile                                # Multi-stage build producing the deployable jar
├── pom.xml
└── LICENSE
```

## Prerequisites

- JDK 17+
- Apache Maven 3.8+
- A running Keycloak instance — **25.0.1** (matching the SPI version this module compiles against; other 20+ versions are likely compatible but not guaranteed)

## Build

```bash
mvn clean package -DskipTests
```

This produces:

```
target/keycloak-login-customer-authenticator-1.0.0.0-SNAPSHOT.jar
```

## Deployment to Keycloak

1. Copy the built jar into Keycloak's `providers` directory:

   ```bash
   cp target/keycloak-login-customer-authenticator-*.jar $KEYCLOAK_HOME/providers/
   ```

2. Rebuild Keycloak so it picks up the new provider:

   ```bash
   $KEYCLOAK_HOME/bin/kc.sh build
   ```

3. Restart Keycloak (`kc.sh start` / `kc.sh start-dev`).

Once started, the provider is registered under the id **`Custom Authenticator-by-Hrouz`**, and appears in the admin console as **"Custom Authenticator By Hrouz"** under *Authentication → Flows*.

## Configuration

Deploying the jar only registers the provider — two additional configuration steps are required on the Keycloak side before it is active for a realm.

### 1. Add the `governmentId` User Attribute

For the government-ID lookup to work, each user that should be reachable by that identifier needs a **`governmentId`** attribute populated:

1. Go to **Realm settings → User profile**.
2. Add a new attribute named `governmentId` (mark it as the field your backend/user-provisioning process populates — e.g. via the Admin REST API, a custom User Storage SPI, or manual entry).
3. Ensure existing/synced users have this attribute set to their numeric national/customer ID.

> The lookup matches attribute values **exactly** and only triggers when the submitted identifier is fully numeric (`\d+`); non-numeric values always fall back to username/email resolution.

### 2. Configure the Authentication Flow

1. Go to **Authentication → Flows**.
2. Duplicate the flow you want to customize (e.g. duplicate *Browser*) or create a new one.
3. In the duplicated flow, **remove/disable** the default **Username Password Form** execution.
4. Click **Add execution**, select **Custom Authenticator By Hrouz**, and add it in the same position.
5. Set its requirement to **Required** (or **Alternative**, depending on your flow design).
6. Bind the updated flow as the realm's **Browser flow** (or to the specific client/flow binding you're customizing) under **Authentication → Bindings**.

## Docker Build

The provided `Dockerfile` builds the module in an isolated Maven/JDK 17 container and stages the resulting jar as `app_kc_login.jar`, ready to be copied into a Keycloak image's `providers` folder in a downstream build step:

```bash
docker build -t keycloak-login-customer-authenticator .
```

> The Dockerfile expects to be built from a parent context containing `technical-services/keycloak-login-customer-authenticator/` (pom.xml + src) — adjust the `COPY` paths if this module is built standalone outside that monorepo layout.

## Logging

The authenticator logs key lifecycle events (`JBossLog`) at `INFO` level — form entry, validation start, and login success — visible in the standard Keycloak server log. No credentials are logged in plaintext; only the submitted identifier is logged for traceability.

## Contact

For any question, issue, or support request regarding this module, reach out to the project owner:

**Mehrez AOUINI** — Software Engineer
📧 [mehrez.aouini@gmail.com](mailto:mehrez.aouini@gmail.com)

## License

Distributed under the **Apache License 2.0** — see [LICENSE](LICENSE) for details.
