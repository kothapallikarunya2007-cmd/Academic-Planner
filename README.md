# Campus Connect

An integrated academic coordination platform built with **Java 25+, Jakarta Servlets, embedded Apache Tomcat, JDBC, and plain HTML/CSS/JavaScript**. Includes a persistent local H2 database and JDBC configuration for PostgreSQL or MySQL.

## Extended academic features

Semester calendars, holidays and faculty leave; A/B weeks; multi-period exams with multiple invigilators; independently reviewed timetable drafts and publication history; configurable days, periods and timezone; durable email/SMS/push gateways; transactional CSV imports and CSV/JSON/iCalendar exports are included. See [the complete workflow and gateway setup](docs/ACADEMIC_FEATURES.md).

## Run on Windows

Install a JDK 25 or later, then open PowerShell in this folder:

```powershell
.\scripts\build.ps1
.\scripts\run.ps1
```

The build script uses an installed Maven, or downloads Maven 3.9.11 into `.tools/` and verifies its SHA-512 checksum. Dependencies stay inside `.m2/` in this project. Internet access is needed for the first build. If your PowerShell policy blocks scripts, run `powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1` for that invocation.

Open **http://localhost:8080**. Stop the server with Ctrl+C.

If port 8080 is occupied, use `$env:PORT = '8081'` before running the script, then open **http://localhost:8081**. This workspace's preview was verified on port 8081.

The delivered workspace may already have a background server running on port 8081. Its logs and process ID are in `runtime/`. Use `.\scripts\stop.ps1` to stop that recorded background process before starting another server against the same H2 database. A server started with `run.ps1` runs in your terminal and stops with Ctrl+C.

With Maven already installed, any OS can use `mvn clean verify`, then `java -jar target/campus-connect-1.0.0.jar`.

### Demo sign-in

| Role | Email | Password |
| --- | --- | --- |
| Administrator | admin@campus.edu | Admin@12345 |
| Faculty | ananya@campus.edu | Faculty@12345 |
| Faculty | rahul@campus.edu | Faculty@12345 |
| Faculty | priya@campus.edu | Faculty@12345 |
| Faculty | arjun@campus.edu | Faculty@12345 |

Demo data is inserted only into an empty database. It includes four courses, four faculty members, and three rooms. The timetable starts unpublished so you can demonstrate generation. Change passwords under **Settings**. For a fresh non-demo database, set `SEED_DEMO=false` and `ADMIN_PASSWORD` before first startup. The admin email is `admin@campus.edu`.

## Demonstration workflow

1. Sign in as administrator. Configure the semester, teaching days, periods and timezone under **Academic calendar**.
2. Inspect **People**, **Courses**, and **Rooms & resources**. Create a second administrator for independent review. Set course week patterns and faculty availability.
3. Generate a timetable draft. In **Schedule review**, preview and submit it. Sign in as the second administrator, approve, then publish.
4. Select a class to draft a move; submit, review and publish that change. Historical publications can be restored as new drafts.
5. Add a multi-period exam with its required invigilator count, then allocate duties.
6. Record holidays and approved date-specific faculty leave; inspect actual dated classes in **Academic calendar**.
7. Demonstrate shared chat, announcements and per-user notification read state. Configure optional delivery preferences under **Settings**.
8. Open **Imports & integrations** for CSV imports, academic JSON, CSV and calendar exports, gateway status and institutional sync.

## Features and permissions

| Module | Administrator | Faculty |
| --- | --- | --- |
| Accounts | Create faculty/admin accounts; change own password | View directory; change own password |
| Resources | Add/remove courses and rooms | View courses and rooms |
| Timetable | Generate/review/publish drafts; draft class moves | View shared timetable; filter own classes |
| Exams | Create multi-period exams; allocate multiple invigilators | View dates and assigned duties |
| Availability | Edit any faculty availability | Edit only own availability |
| Communication | Shared chat, announcements, notifications | Shared chat and notifications |
| Reporting | Workload, utilization, CSV, conflict review | No report/export permission |

Authorization is enforced by the server, not only by hidden UI controls. The chat is one institution-wide room, not private direct messaging. The directory and academic schedules are visible to all authenticated members.

## Scheduling rules

- Semester dates, teaching weekdays, ordered periods and campus timezone are configurable. Courses run every week, week A or week B; week A starts on the Monday of the semester's first week.
- Faculty, cohort, capacity and room conflicts are checked against actual dated teaching occurrences. Holidays and approved faculty leave cancel affected occurrences; replacements are not automatically created.
- Exams occupy one or more consecutive configured periods and require one or more distinct invigilators. Course instructors cannot supervise their own exams. Every occupied period, faculty leave and concurrent duty is checked.
- Generation and allocation use bounded backtracking (200,000 nodes), with atomic rollback on failure. Allocation favors lower workloads; optimal balance is not guaranteed.
- Generated and manually moved teaching schedules become drafts. A different administrator must approve them before publication. Changes to academic inputs make existing drafts stale.
- Coordinated writes use a shared database row lock. Removing holidays/leave revalidates reinstated teaching. Course deletion removes associated classes and exams; referenced rooms cannot be removed.
- Publication history preserves assignment sets. Restoration creates a draft against current resources and calendar settings; it is not a full database restore.

## Configuration

Environment variables are read directly; `.env` is **not** automatically loaded.

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | `8080` | HTTP port |
| `BIND_ADDRESS` | `127.0.0.1` | HTTP listening address |
| `DB_URL` | `jdbc:h2:./data/campus;DB_CLOSE_ON_EXIT=FALSE` | JDBC URL |
| `DB_USER` | `sa` | Database username |
| `DB_PASSWORD` | empty | Database password |
| `SEED_DEMO` | `true` | Seed sample data on an empty database |
| `ADMIN_PASSWORD` | `Admin@12345` | Initial administrator password |
| `SECURE_COOKIE` | `false` | Set `true` behind HTTPS |
| `CHAT_PORT` | unset | Enable optional loopback TCP chat server |

PostgreSQL example (create an empty database and user first):

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/campus'
$env:DB_USER = 'campus_app'
$env:DB_PASSWORD = '<your database password>'
.\scripts\run.ps1
```

MySQL example: use `jdbc:mysql://localhost:3306/campus` with an empty database using InnoDB. Drivers are packaged with the application. The schema uses portable DDL, explicit foreign keys, UUID text IDs, and parameterized queries. Automated tests run against H2; PostgreSQL/MySQL configuration has not been verified against live servers in this workspace.

H2 data persists in `data/campus.mv.db`. Back up the data directory while the application is stopped. A packaged JAR uses paths relative to the directory where it is launched.

## Real-time communication and Java sockets

Browser chat uses HTTP POST for messages and **Server-Sent Events (SSE)** for live invalidation; clients fetch the latest persisted conversation after an event. Async Servlets avoid holding a request thread per connection. Event streams reconnect every minute, send heartbeats every 15 seconds, and have a 100-connection cap. A 30-second browser refresh provides fallback and cross-process visibility. The browser shows the latest 100 messages; all sent messages remain in the database.

The optional TCP teaching interface uses `ServerSocket`, `Socket`, a bounded thread pool (16 simultaneous connections), JSON lines, and the same JDBC service. It listens on loopback only and has a 60-second idle timeout. Enable it before starting the server:

```powershell
$env:CHAT_PORT = '9090'
.\scripts\run.ps1
```

In a second interactive terminal:

```powershell
java -cp target/campus-connect-1.0.0.jar edu.campus.ChatClient
```

Enter your email/password, type messages, use `/history`, and exit with `/quit`. Messages sent from the TCP client immediately notify browser clients. TCP history is fetched on request; the TCP interface does not push messages itself. This local educational protocol is plaintext and must not be exposed as a public network service.

## REST API and Postman

Import `docs/campus-connect.postman_collection.json`. Default base URL: `http://localhost:8080`. Run **Login admin** first; Postman's cookie jar preserves the session. Every POST requires JSON plus `X-Campus-Request: 1`. No permissive CORS policy is enabled.

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/login`, `/api/logout` | Session authentication |
| GET | `/api/me`, `/api/state` | Current account and coordinated data |
| POST | `/api/users`, `/api/rooms`, `/api/courses`, `/api/exams` | Create records |
| POST | `/api/generate`, `/api/allocate` | Create timetable drafts / publish exam duties |
| POST | `/api/move`, `/api/availability`, `/api/delete` | Validated changes |
| GET / POST | `/api/messages` | Latest chat history / send message |
| GET | `/api/events` | SSE event stream |
| POST | `/api/announce`, `/api/notifications/read` | Announcements / read status |
| POST | `/api/password` | Change own password using current password |
| GET | `/api/report` | Administrator CSV export |

API errors return JSON `{ "error": "..." }` with 401 (not signed in), 403 (forbidden), 409 (invalid input/conflict), 413 (oversized body), 429 (login throttled), or 500 (unexpected failure). Unknown GET routes return 404. Input bodies are limited to 16 KiB, except CSV import requests (1 MiB). Additional academic, workflow, delivery and exchange endpoints are documented in [Academic features](docs/ACADEMIC_FEATURES.md).

## Architecture and syllabus mapping

```text
Browser UI ── HTTP / SSE ──> ApiServlet ──> CampusService ──> JDBC Database
                                               │                │
Java ChatClient ── TCP ──> SocketChatServer ──────┘          H2 / PostgreSQL / MySQL
                                               │
                                            Scheduler
```

| Java concept | Implementation |
| --- | --- |
| Servlets | `ApiServlet`, static resource servlet, HTTP sessions, async SSE |
| JDBC | `Database`: prepared statements, result mapping, transactions, foreign keys, row locks |
| Collections | `Scheduler`: lists, sets, maps, constraint checking, workload counts |
| Multithreading | Tomcat request workers, scheduled SSE executor, bounded TCP worker pool |
| Exception handling | Atomic rollback, user-friendly API errors, logged unexpected failures |
| Client-server sockets | `SocketChatServer` and `ChatClient` |
| Security | PBKDF2 password hashing with random salts, role checks, HttpOnly/SameSite session cookies, same-origin custom header, HTML escaping, CSP, CSV formula escaping |

## Tests and project layout

`mvn verify` runs 22 JUnit tests against isolated in-memory databases. Tests cover credentials and roles, scheduling constraints, rejected moves, impossible schedule rollback, balanced invigilation, failed allocation rollback, faculty availability ownership, concurrent scheduling/chat, notification read state, and referential integrity. `ApiIntegrationTest` additionally exercises real Tomcat HTTP endpoints, cookies, authorization, live events, and the TCP bridge.

```text
src/main/java/edu/campus/    Java application and scheduling services
src/main/resources/web/     Responsive frontend
src/test/java/edu/campus/    Automated tests
scripts/                   Windows build/run helpers
docs/                      Postman collection and design notes
```

This is a local academic project implementation. Before institutional deployment, add HTTPS, managed secrets, account recovery and lifecycle administration, audit logs, database migrations, connection pooling, production monitoring, retention policies, and load tests. Notifications are in-app with optional configurable email/SMS/push HTTPS gateways. Live gateways and institutional connections need their own configuration and verification.

Technology references: [Tomcat 10.1 documentation](https://tomcat.apache.org/tomcat-10.1-doc/) and [Maven 3.9.11 release notes](https://maven.apache.org/docs/3.9.11/release-notes.html).

## Vercel deployment

See [Vercel deployment instructions](docs/VERCEL_DEPLOYMENT.md) for container packaging, production configuration, database initialization, and verification. Production uses shared database sessions with a fixed 30-minute lifetime.
