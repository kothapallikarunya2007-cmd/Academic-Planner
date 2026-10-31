# Vercel deployment

This application runs as a Java 25 container, serving both the browser UI and `/api/*` on the same origin. Use Vercel's container-image support (currently beta), which detects `Dockerfile.vercel`. Do not select a static output directory or deploy only the web resource folder.

## Required setup

1. Provision an external PostgreSQL database through Vercel Marketplace. Keep preview and production databases separate. Never connect an untrusted preview to production data.
2. Configure these runtime variables in each Vercel environment:
   - `DB_URL`: JDBC PostgreSQL URL with `sslmode=verify-full`, for example `jdbc:postgresql://HOST:5432/DATABASE?sslmode=verify-full&connectTimeout=10&socketTimeout=30`. Supply the provider CA through the JDBC `sslrootcert` option if needed. A Marketplace `DATABASE_URL` is not automatically converted to JDBC.
   - `DB_USER` and `DB_PASSWORD`: database credentials stored as secrets.
   - `APP_ENV=production`, `SEED_DEMO=false`, `SECURE_COOKIE=true`, `BIND_ADDRESS=0.0.0.0` (also set in the image).
   - Let the platform supply `PORT`. Do not set `CHAT_PORT`.
3. Build with `mvn --batch-mode verify` using JDK 25. Before the first deployment, run the schema bootstrap **once**, from a trusted environment with the same database variables and a unique `ADMIN_PASSWORD` of at least 16 characters:

   ```sh
   java -jar target/campus-connect-1.0.0.jar --init-db
   ```

   The administrator email is `admin@campus.edu`. This command creates missing tables without replacing existing data. It does not rotate existing passwords. Do not run concurrent bootstrap commands. Remove `ADMIN_PASSWORD` from the runtime environment after initialization. Existing demo accounts must be removed or have their passwords changed before exposing an existing database.
4. Import the repository into Vercel with its root directory unchanged and container-image support enabled. Deploy a preview against the preview database first.
5. Verify login, `/api/me`, timetable generation, messages, logout, and login persistence across instances. Only then release to production.

## Operational behavior

Sessions are random opaque cookies with SHA-256 digests stored in PostgreSQL and a fixed 30-minute expiry. Logout revokes the shared session. Login throttling is shared per normalized email (15 attempts per minute); add platform-level request rate limiting for broad abusive traffic. Expired session and throttle rows are cleaned during login operations.

Tomcat working files live in the OS temporary directory in production. Academic data is never stored there. Schema setup is deliberately excluded from normal production startup. Future schema changes need reviewed, ordered migrations.

SSE delivers immediate updates within an instance; the existing 30-second browser refresh provides cross-instance visibility. This is eventual synchronization, not a distributed push service. The optional raw TCP teaching interface remains local-only.

The Docker build runs the test suite before packaging. Local tests use H2; verify PostgreSQL connectivity, SSL certificates, schema bootstrap, and the container on your actual Vercel project. Container eligibility, a database, and runtime secrets are deployment prerequisites.

Reference: https://vercel.com/docs/functions/container-images

## Academic feature upgrade and delivery workers

See [Academic features](ACADEMIC_FEATURES.md) for schema upgrade and gateway variables. Run the new `--init-db` once with old instances stopped before starting the new application. Notification delivery uses an in-process worker with a persistent leased outbox. Hosting that suspends or scales to zero needs a continuously running worker instance or external scheduling; do not promise timely delivery without verifying this lifecycle. Gateway acceptance does not confirm recipient delivery.
