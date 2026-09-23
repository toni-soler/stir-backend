# STIR backend 0.2.0-SNAPSHOT

Java 21 / Boot 3.4.4; public Core binary es.idynamicsax.idax:idax-core:0.4.0. Source is Apache-2.0, Core retains its binary license. Public osTRIS authentication/module configuration adapted under Apache-2.0 (Copyright 2026 Toni Soler).

`mvn -s .mvn/public-settings.xml clean verify`

Start the full development environment from sibling stir-main. Flyway runs after public Core migrations. Swagger: /swagger-ui/index.html; health: /actuator/health/readiness. Bearer authentication and stir.listings.read/create/update permissions protect all listing routes. Owner checks apply in addition to permissions.

POST /api/stir/tenants/{tenantId}/listings creates ACTIVE. GET lists with q, direction, category, resourceKind, status, mine, page and size filters. GET/PUT /{id}; POST /{id}/close. Update/close require version. No economic endpoint or upload is implemented.
