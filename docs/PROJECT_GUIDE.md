# Campus Connect: user guide, code explanation, and viva preparation

**Updated workflow:** Read [Academic features](ACADEMIC_FEATURES.md) first. It documents semester calendars, A/B weeks, leave, multi-period exams, independent timetable approval, gateways and data exchange. The foundational explanations below describe the original core; old direct-publication and weekly-only walkthroughs are superseded by that guide.

This guide explains the Java fundamentals in this project. The Maven compiler target is Java 25. The default database is H2. Browser updates use Server-Sent Events (SSE), and an optional TCP client/server demonstrates Java sockets. Spring Boot is not used.

## 1. What the project does

Campus Connect helps an academic institution coordinate teaching schedules, room allocation, examination invigilation, and communication in one application.

The problem it addresses is that these activities depend on one another. A faculty member assigned to teach a class should not be assigned to invigilate an exam at the same time. A room cannot hold two activities in the same period. The same student group cannot attend two classes simultaneously. When a schedule changes, people need to see the updated information.

The application stores shared academic data, validates assignments, publishes valid schedules, and gives faculty and administrators a shared conversation and notification area.

**A short presentation introduction:**

> My project is Campus Connect, an integrated academic coordination and communication platform. It combines weekly timetable generation, classroom allocation, exam invigilation, shared chat, and notifications. The backend uses Java Servlets and JDBC on embedded Tomcat. The default database is H2. A bounded backtracking algorithm checks scheduling constraints, and transactions protect coordinated changes. Browser updates use Server-Sent Events, while an optional TCP interface demonstrates Java socket programming.

## 2. The actual technology stack

| Technology | Its role in this project |
| --- | --- |
| Java | Backend application and scheduling logic |
| JDK 25 or later | Required by the current `maven.compiler.release` setting of 25 |
| Jakarta Servlets | Receive browser requests and return responses |
| Embedded Apache Tomcat | Runs the Servlets inside the application process |
| JDBC | Connects Java code to the relational database |
| H2 | Default database; stores application records in a local file |
| PostgreSQL/MySQL JDBC drivers | Included for optional database configuration; live external databases were not verified here |
| HTML | Page structure |
| CSS | Layout, colors, typography, responsive presentation |
| JavaScript | Forms, API calls, rendering, filters, live updates |
| Jackson | Converts JSON to Java data structures and Java values back to JSON |
| Maven | Downloads dependencies, compiles code, runs tests, packages the application |
| JUnit | Automated tests |
| SSE / `EventSource` | Live server-to-browser change notifications |
| `Socket` / `ServerSocket` | Optional terminal chat client and server |

You do not need to install MySQL or a separate Tomcat installation to run the default application. Embedded Tomcat and the H2 driver are included in the packaged JAR.

## 3. How to start and stop it

### Start

Open PowerShell and use the existing project folder:

```powershell
cd 'C:\Users\dell\OneDrive\Documents\ChatGPT\JAVA PROJECT'
java -version
```

The current project needs Java 25 or later. Java 27 is installed on this computer. On this machine, select its JDK for Maven:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-27'
$env:PORT = '8081'
.\scripts\build.ps1
.\scripts\run.ps1
```

The build command must finish successfully before you run the project. The run command keeps running because it is serving the application. Leave that terminal open and visit:

**http://localhost:8081**

The code defaults to port 8080 if `PORT` is not set. Using 8081 makes the commands and browser address consistent with the original demonstration.

The build script uses Maven to run `verify`, which compiles, tests, and packages the application. The JAR is `target/campus-connect-1.0.0.jar`. Once that JAR is up to date, later starts only need the port setting and `run.ps1`.

**After changing source code, build again.** `run.ps1` only builds automatically when the JAR is missing; it does not detect newer source files.

### Stop

- For a server started in your PowerShell terminal using `run.ps1`, press **Ctrl+C** in that terminal.
- For the previously recorded background server, use `.\scripts\stop.ps1`. That script checks the recorded PID and expected Java command before stopping it.
- Stopping the server does not erase the database.
- Start only one application process against the default embedded H2 database file. If an old instance is still running, stop it first.

### Common startup problems

| What you see | Meaning and next step |
| --- | --- |
| `release version 25 not supported` | Maven is using an older JDK. Set `JAVA_HOME` to the installed JDK 27 path above, then rebuild. |
| `UnsupportedClassVersionError` | The Java runtime is too old for the compiled application. Check `java -version` and use Java 25 or later. |
| Port/address already in use | Another process owns that port. Stop the existing instance or choose another `PORT` and use the matching browser URL. |
| Database already in use | Another process may have the H2 file open. Stop that application instance. |
| Browser cannot connect | Confirm the server has started, the terminal remains open, and the browser port matches `PORT`. |
| Scripts are disabled | If allowed by your environment, run the script through `powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1`; do not change machine-wide policy just to run it. |

## 4. Accounts and permissions

Default seeded accounts:

| Role | Email | Initial password |
| --- | --- | --- |
| Administrator | `admin@campus.edu` | `Admin@12345` |
| Faculty | `ananya@campus.edu` | `Faculty@12345` |
| Faculty | `rahul@campus.edu` | `Faculty@12345` |
| Faculty | `priya@campus.edu` | `Faculty@12345` |
| Faculty | `arjun@campus.edu` | `Faculty@12345` |

These apply when the demo database was created with the default settings and the passwords have not since changed. Demo data is created only when the users table is empty.

An administrator can create accounts, add/remove courses and rooms, generate and move classes, add/remove examinations, allocate invigilators, edit faculty availability, post announcements, and access reports.

A faculty member can view shared academic information, filter the timetable, edit their own availability, chat, read notifications, and change their own password. Faculty cannot generate schedules, create resources, allocate invigilators, or access the administrator CSV report.

Server-side permission checks enforce this. Hiding an administrator button in the frontend is only a presentation choice; it is not the security boundary.

## 5. Use the screens in this order

### People

The administrator selects **Add member** and supplies a name, email, initial password, role, and department. Email addresses must be unique; passwords must be at least 10 characters.

A faculty account must exist before it can be assigned to a course. A faculty role is required for teaching and invigilation assignments. Account deactivation, role editing, and password recovery are not implemented.

### Rooms & resources

Add a room name and student capacity. A class or exam with 60 students needs a room with capacity of at least 60. The scheduler considers the rooms you have entered; it does not automatically divide a large cohort across multiple rooms.

Room names must be unique. Rooms already referenced by classes or exams cannot be removed.

### Courses

Add a course code, title, assigned faculty member, cohort, student count, and weekly session count.

Example:

```text
Course code: CS401
Title: Software Engineering
Faculty: Ananya Sharma
Cohort: CSE Semester 7 Section A
Students: 40
Sessions per week: 3
```

This requires three one-period teaching sessions each week. A **cohort** is the student group attending the course. Use the same cohort text consistently across courses taken by that group, because scheduling compares stored cohort values. The code does not provide a separate cohort ID table.

The default sample contains four courses with three sessions each: `4 × 3 = 12` required weekly classes.

### Availability

Select a faculty member as administrator, or use your own availability as faculty. Click a period to toggle between available and unavailable.

Do this before generating schedules. Availability is a recurring weekday/period restriction, not date-specific leave. If a published class or invigilation duty already occupies the period, the change is rejected. Move the class, or remove and recreate the affected exam as appropriate, before applying the restriction.

### Timetable

Select **Generate timetable**, read the replacement notice, then select **Generate & publish**.

The timetable has five days and six periods each day:

| Internal slot number | Displayed time |
| --- | --- |
| 0 | 09:00–10:00 |
| 1 | 10:00–11:00 |
| 2 | 11:15–12:15 |
| 3 | 13:00–14:00 |
| 4 | 14:00–15:00 |
| 5 | 15:15–16:15 |

The backend stores Monday as day 0 and Friday as day 4. The UI displays names and times.

Generating replaces the entire weekly lesson schedule, including manual moves. If generation fails, the earlier publication is retained. Adding a new course does not automatically schedule it; generate again when ready.

As administrator, click an existing class to move its day, period, or room. The server validates the proposed change. A move that double-books a faculty member, student cohort, or room is rejected. A too-small room is also rejected.

The faculty filter changes the displayed view; it does not alter database assignments.

### Examinations

Select **Add examination**. Choose a course, room, date, and period. Choose a period that does not clash with the course cohort's recurring classes or the room's recurring bookings.

Creating an exam and allocating its invigilator are separate operations. A new exam initially shows **Awaiting allocation**. Then select **Allocate invigilators** and confirm publication.

The allocator assigns one faculty member per exam. The faculty member must not teach that course, must be available, must not teach another class in that period, and must not invigilate another exam at the same date/time.

Faculty with fewer duties in the allocation being calculated are tried first. This encourages balance; it does not guarantee a mathematically optimal distribution.

The current UI supports adding and deleting exams, not editing an existing exam date or manually choosing its invigilator. To change an exam's date or room, remove it and recreate it, then allocate again.

### Campus chat

Open the application in two independent browser sessions: for example, a normal window for administrator and a private/incognito window for faculty. Two ordinary tabs in the same browser profile normally share the login cookie, so they are unsuitable for demonstrating two different roles simultaneously.

Open **Campus chat** in both sessions, send a message, and observe the other session update.

There is one shared campus conversation. Messages are visible to authenticated campus members. Each message allows up to 2,000 characters. The interface displays the latest 100 messages; older records remain in the database. Private messaging, attachments, and typing indicators are not implemented.

### Notifications

Schedule publication, manual class moves, exam creation, invigilation publication, and resource removal can create in-app notifications. Administrators can also post announcements manually.

**Mark all as read** changes read state only for the current account. It does not delete notifications or mark them read for everyone. Every possible data change does not necessarily create a notification; the specific service operations determine that.

### Reports

Administrators can inspect teaching coverage, teaching workload, invigilation counts, room utilization, detected assignment conflicts, and timetable CSV export.

```text
Teaching coverage = published lesson count / required lesson count × 100
Room utilization = published lesson count / (room count × 30) × 100
```

Thirty comes from five days times six periods. The utilization metric counts weekly teaching room-periods; it does not include exam usage or percentage of individual seats occupied. The displayed percentages are rounded.

Zero detected conflicts does not mean every required session has been published or every exam has an invigilator. Coverage and pending allocation counts must also be checked.

### Settings

View your account details, change your own password using the current password, or sign out.

## 6. How one browser action travels through the code

```text
User clicks a button
        ↓
app.js sends an HTTP request with fetch()
        ↓
Tomcat routes /api/* to ApiServlet
        ↓
ApiServlet checks request, session, and endpoint
        ↓
CampusService checks role and validates business input
        ↓
Scheduler performs scheduling/assignment work where needed
        ↓
Database executes SQL through JDBC
        ↓
Transaction commits, or rolls back on failure
        ↓
Servlet returns JSON and signals live clients after a successful change
        ↓
Frontend fetches updated data and renders the screen
```

For timetable generation, the particular route is:

```text
POST /api/generate
→ ApiServlet.service()
→ CampusService.mutate("generate", ...)
→ Database.transaction(...)
→ Scheduler.generate(connection)
→ store lessons and notification
→ commit
→ ApiServlet.changed()
→ browser refreshes state
```

This is a layered application: presentation, request handling, business logic, and database access have separate responsibilities. The layers still have some practical coupling; this is not a claim of strict enterprise clean architecture.

## 7. What every main source file does

| File | Responsibility |
| --- | --- |
| `App.java` | Entry point; creates the database/service, configures Tomcat and routes, optionally starts TCP chat |
| `ApiServlet.java` | HTTP routing, sessions, JSON input/output, live event streams, CSV export, API errors |
| `CampusService.java` | Login, user/resource operations, permission checks, input validation, messages, notification read state |
| `Database.java` | JDBC connections, prepared statements, transactions, schema creation, initial data |
| `Scheduler.java` | Timetable search, invigilation allocation, conflict checks |
| `Passwords.java` | Salted PBKDF2 password derivation and verification |
| `SocketChatServer.java` | Optional TCP server; bounded client worker pool; login/message/history protocol |
| `ChatClient.java` | Interactive terminal client for that TCP protocol |
| `index.html` | Initial page, frontend script/style links, toast and dialog containers |
| `app.js` | UI rendering, form actions, API calls, filters, SSE connection |
| `style.css` | Styling and responsive layouts |
| `pom.xml` | Build settings, dependencies, test and packaging plugins |
| `CampusServiceTest.java` | Nine tests covering service/database/scheduling behavior |
| `ApiIntegrationTest.java` | Two tests using real embedded Tomcat and the TCP interface |

## 8. How timetable generation works

The implemented algorithm is **bounded recursive backtracking**.

1. Load courses, rooms, faculty unavailability, and exams.
2. Sort courses by larger student counts first, then more required sessions, then code. Sort rooms by smaller capacity first, then name.
3. Convert every required weekly session into a scheduling task.
4. Take the next task and try possible periods, weekdays, and rooms.
5. Reject a candidate if capacity is insufficient, the faculty member is unavailable, or the candidate clashes with an already placed faculty member, cohort, or room. Relevant exam bookings also block candidates.
6. Temporarily add a valid placement and recursively attempt the next task.
7. If later tasks cannot be placed, remove the previous tentative placement and try another candidate. This is backtracking.
8. If all tasks fit, replace the stored lessons and create the publication notification in the transaction.
9. If no complete arrangement is found within the configured search, report failure and retain the earlier publication.

Example: suppose the algorithm tentatively puts Java on Monday at 09:00 in Room A. A later course may have so few available options that it requires that booking. Backtracking allows the algorithm to reconsider the earlier Java placement instead of immediately giving up.

The search counter stops a branch once it exceeds 200,000 recursive search visits. This is a search bound, not a wall-clock deadline or a count of milliseconds. Backtracking has exponential worst-case behavior; the implementation is intended for an academic demonstration rather than guaranteed fast scheduling of every large institution.

The code favors earlier periods across weekdays, but does not guarantee evenly distributed daily workloads, one session per course per day, or an optimal timetable.

## 9. How the database keeps changes consistent

The relevant method is `Database.transaction(...)`.

```java
c.setAutoCommit(false);
```

This stops individual statements from being committed automatically. The method also locks the single coordination row with:

```sql
SELECT id FROM coordination_lock WHERE id=1 FOR UPDATE
```

Transactions using that row wait for one another, so coordinated modifications do not simultaneously operate as if they each had exclusive access to the schedule.

On success, `commit()` saves all changes in that transaction. On an exception, `rollback()` cancels its uncommitted changes.

For example, a class move first changes a row inside a transaction. If conflict validation then detects that its faculty member is already teaching, an exception is thrown and the change is rolled back. The previously published class remains intact.

This is a database lock, distinct from Java's `synchronized`. The code uses `synchronized` separately for the in-memory login-attempt map.

Chat writes and coordinated state reads also use the same transaction helper. This makes the design simple but means a long scheduling transaction can delay other operations. It is not a high-throughput production concurrency design.

## 10. How chat and live updates work

### Browser communication

1. Sending a message makes a `POST /api/messages` request.
2. The service inserts the message using JDBC and commits it.
3. `ApiServlet.changed()` increments a revision number and writes an SSE event to connected browser clients.
4. The browser's `EventSource` receives the event.
5. JavaScript fetches updated state, and fetches chat history if the chat screen is active.

The SSE event contains a change revision, not the full message. SSE carries server-to-browser events. The browser still uses separate HTTP requests to send data to the server.

An async Servlet keeps the event connection open without keeping a request-processing thread dedicated to each idle connection. A scheduled executor sends heartbeats every 15 seconds. Connections time out after about 60 seconds and `EventSource` reconnects. The code attempts to cap live listeners at 100 and also refreshes browser data every 30 seconds as a fallback.

These features provide near-immediate updates under normal local conditions; they do not guarantee hard real-time delivery deadlines.

### Optional Java TCP demonstration

Before starting the application, enable the socket server:

```powershell
$env:PORT = '8081'
$env:CHAT_PORT = '9090'
.\scripts\run.ps1
```

If the main server is already running, stop it before restarting with these settings. In a second interactive terminal in the project folder, run:

```powershell
java -cp target/campus-connect-1.0.0.jar edu.campus.ChatClient
```

The client asks for email and password, with password entry hidden. Type a message, `/history` to fetch the shared conversation, or `/quit` to exit.

`ServerSocket` listens for connections. Each accepted `Socket` represents a connected client. The server uses a thread pool with a maximum of 16 workers to handle clients concurrently. The protocol exchanges one JSON object per line. TCP chat has a 60-second idle timeout and only listens on loopback.

Messages use the same database service as browser chat. A TCP message signals browser live updates. TCP clients fetch history explicitly; the TCP interface itself does not push incoming chat messages.

## 11. Database terminology and tables

| Term | Meaning | Example here |
| --- | --- | --- |
| Database | Organized persistent data storage | H2 database file |
| Table | Records with a common structure | `users`, `courses` |
| Row / record | One stored item | One faculty account |
| Column / field | One property of a record | `email`, `capacity` |
| Schema | Definitions of tables and constraints | SQL in `Database.initialize()` |
| SQL | Language used to define and query relational data | `SELECT`, `INSERT`, `UPDATE`, `DELETE` |
| Primary key | Uniquely identifies a row | `users.id` |
| Foreign key | Connects a row to an existing row in another table | `courses.faculty_id → users.id` |
| Unique constraint | Forbids duplicate values or combinations | Unique email; unique room/day/slot booking |
| JOIN | Combines related data from tables | A lesson plus course title and room name |
| Transaction | Related operations treated as one unit | Publish lessons and their notification |
| Commit | Save a successful transaction | `c.commit()` |
| Rollback | Cancel a failed transaction's uncommitted work | `c.rollback()` |
| Persistence | Records remain after restarting the app | Stored lessons and chat messages |

The tables are:

| Table | Stores |
| --- | --- |
| `users` | Accounts, roles, departments, password hashes |
| `rooms` | Room names and student capacities |
| `courses` | Course definitions and assigned faculty |
| `unavailable` | Recurring faculty unavailable periods |
| `lessons` | Generated weekly class placements |
| `exams` | Dated exams and optional assigned invigilators |
| `messages` | Shared chat history |
| `notifications` | Shared update messages |
| `notification_reads` | Which user has read which notification |
| `coordination_lock` | One row used for transaction coordination |

By default, academic records persist in `data/campus.mv.db`. Ordinary server restarts do not recreate or clear them. In-memory data such as active sockets and live listeners is recreated; do not rely on a server restart preserving sign-in state.

## 12. Essential Java terminology

| Term | Plain explanation | Example from the project |
| --- | --- | --- |
| JDK | Java Development Kit; includes compiler and tools | Used to build the project |
| JVM | Java Virtual Machine; executes Java bytecode | Runs the application |
| Compiler | Translates Java source into bytecode | `javac`, invoked through Maven |
| Bytecode | Instructions stored in `.class` files | Compiled application classes |
| JAR | Archive that packages Java classes/resources | Runnable `campus-connect-1.0.0.jar` |
| Package | Namespace grouping related code | `edu.campus` |
| Import | Lets code refer to types from another package by short names | `import java.sql.*;` |
| Class | Definition of a type and its behavior | `CampusService` |
| Object | An instance created from a class | `new CampusService(db)` |
| Constructor | Initializes a new object | `CampusService(Database db)` |
| Method | A named operation | `generate`, `login`, `transaction` |
| Parameter | Named input in a method declaration | `Connection c` in `generate(Connection c)` |
| Argument | Actual value supplied when calling a method | The database object passed in `new CampusService(db)` |
| Return value | Result supplied by a method | `login()` returns account data or `null` |
| Variable | Named storage for a value/reference | `int visits` |
| Reference | Value identifying an object | The `db` reference used to access `Database` |
| `public` | Accessible from other classes subject to normal Java access rules | `public final class App` |
| `private` | Accessible only within its declaring class | Scheduler's `visits` field |
| `static` | Belongs to the class, not an individual instance | `Database.id()` |
| `final` | Meaning depends on use: no reassignment, no overriding, or no subclassing | `final class` prevents subclassing; a final object reference does not make its object immutable |
| `void` | Method has no returned value | `void generate(...)` |
| `null` | No object reference is present | Failed `login()` result |
| `var` | Compiler infers a local variable's static type | `var courses = rows(...)`; Java remains statically typed |
| `record` | Concise data-carrier class with generated accessors and value behavior | `Placement` |
| Generic type | Parameterized type for type checking/reuse | `List<Placement>` |
| Interface | Contract implemented by a class or lambda | `Database.Work<T>`, `AutoCloseable` |
| Inheritance | A class extends another class | `ApiServlet extends HttpServlet` |
| Overriding | A subclass supplies its implementation of an inherited method | Servlet `service(...)` |
| `@Override` | Compiler-checked declaration of an override | On the Servlet `service` method |
| Lambda | Compact implementation of a functional interface | `c -> { ... }` transaction work |
| Recursion | Method calls itself on a smaller/next part of a problem | `place(...)`, `assign(...)` |
| Exception | An object representing an abnormal condition | Invalid input or SQL failure |
| `throw` | Raises an exception | Reject a conflicting assignment |
| `throws` | Declares exceptions a method may pass to its caller | `throws SQLException` |
| `try/catch` | Executes code and handles exceptions | Servlet API error handling |
| `finally` | Cleanup block that runs when control leaves the try/catch under normal Java execution rules | TCP client tracking cleanup |
| Try-with-resources | Closes `AutoCloseable` resources automatically | JDBC connections and statements |

### Collections used here

| Collection | Purpose | Actual use |
| --- | --- | --- |
| `List` / `ArrayList` | Ordered sequence; can contain duplicates | Scheduling tasks and placements |
| `Map` / `HashMap` | Key-to-value lookup | Faculty ID to duty count |
| `LinkedHashMap` | Map that preserves insertion order | JSON-like rows and chosen assignments |
| `Set` / `LinkedHashSet` | Unique items; insertion order retained | Avoid duplicate conflict descriptions |
| `ConcurrentHashMap.newKeySet()` | Set supporting concurrent access | Connected sockets and live listeners |

`Map<String,Object>` is used frequently for database rows and JSON-style data. The keys are field names, and values may have different Java types. This is flexible but offers less compile-time domain type safety than dedicated `User`, `Course`, and `Room` classes.

### Object-oriented concepts you can point to

- **Encapsulation:** private fields and helper methods hide internal details, such as the scheduler's recursion and search counter.
- **Abstraction:** callers use `Database.transaction()` without repeating connection/commit/rollback management.
- **Inheritance:** `ApiServlet` extends `HttpServlet`.
- **Polymorphism:** overridden Servlet methods and interface implementations can be invoked through their parent/interface contracts.
- **Composition:** `CampusService` contains a reference to a `Database` object.

## 13. Web, JDBC, and concurrency terminology

| Term | Meaning in this project |
| --- | --- |
| Frontend | Browser code that users see and interact with |
| Backend | Java code handling requests, rules, and persistence |
| Client | Program requesting a service, such as the browser or `ChatClient` |
| Server | Program receiving requests/connections and providing a service |
| Localhost / loopback | The computer running the application; another device's localhost points to that other device |
| Port | A numbered network endpoint; HTTP uses the configured 8080/8081 and optional TCP chat uses 9090 |
| HTTP | Browser/server request-response protocol |
| API | Defined interface through which programs interact |
| Endpoint | One API address, such as `/api/generate` |
| GET | Used here for reading state, history, identity, reports, and events |
| POST | Used here for login and state-changing operations |
| JSON | Text representation for structured data; Jackson and JavaScript convert it |
| Servlet | Java web component managed by Tomcat to process requests |
| Servlet container | Runtime managing Servlets and HTTP handling; Tomcat here |
| Session | Server-side information associated with a signed-in browser |
| Cookie | Browser-stored value; the session cookie identifies the server-side session |
| Authentication | Checking who the user is, using email/password |
| Authorization | Checking what that user is allowed to do |
| Validation | Checking whether supplied data meets required rules |
| JDBC | Standard Java interfaces for relational database access |
| JDBC driver | Database-specific implementation used by those interfaces |
| `Connection` | Java handle for a database connection |
| `PreparedStatement` | SQL statement with separately bound parameters |
| `ResultSet` | Rows returned by a database query |
| Thread | One execution path within a process |
| Multithreading | Multiple execution paths can make progress concurrently |
| Concurrency | Handling overlapping tasks; it does not always mean simultaneous execution on separate CPU cores |
| Executor / thread pool | Manages a bounded or scheduled set of worker threads |
| Race condition | An outcome improperly depends on timing of competing operations |
| Lock | Coordination mechanism controlling competing access |
| `synchronized` | Java mechanism providing mutual exclusion on a monitor |
| `AtomicLong` | Thread-safe atomic operations on a long value; used for the event revision |
| Socket | Endpoint of a network connection |
| `ServerSocket` | Accepts incoming TCP connections |
| SSE | Server-to-browser event stream; browser uses `EventSource` |
| Polling | Periodically asking the server for updates; 30-second refresh is the fallback here |
| `fetch()` | JavaScript function making HTTP requests |
| `async` / `await` | JavaScript syntax for Promise-based asynchronous operations; not the same as creating Java worker threads |
| DOM | Browser's object representation of the document, updated by the frontend |

These are HTTP JSON endpoints with REST-style usage. The current API also has action endpoints such as `/generate` and uses POST for deletion; describe the concrete API rather than claiming strict REST conformance.

## 14. Small code examples to understand

### Parameterized SQL

```java
var users = rows(c, "SELECT * FROM users WHERE email=?", email);
```

`?` is a placeholder. `Database.rows()` binds `email` as a value using a `PreparedStatement`. Input is not concatenated into SQL syntax. This helps prevent SQL injection for these parameter values.

### Faculty workload map

```java
Map<String, Integer> load = new HashMap<>();
load.merge(fid, 1, Integer::sum);
```

The key is a faculty ID. The value is the number of duties assigned in the tentative allocation. `merge` initializes the count to one or adds one to its existing value. `Integer::sum` is a method reference.

### Transaction callback

```java
db.transaction(c -> {
    exec(c, "INSERT INTO messages VALUES(?,?,?,?)",
         id(), userId, body.trim(), System.currentTimeMillis());
    return null;
});
```

The lambda receives the transaction's connection. The SQL inserts one message. `return null` supplies the callback's result because this operation has no meaningful result value; it is not a stored database field in this statement.

### Servlet inheritance

```java
public final class ApiServlet extends HttpServlet {
    @Override
    protected void service(HttpServletRequest req,
                           HttpServletResponse res) throws IOException {
        // Actual implementation routes and handles requests.
    }
}
```

This excerpt shows the structure, with the real method body omitted. `extends` establishes inheritance, `@Override` verifies the override, `req` represents the request, and `res` is used to construct the response.

### Browser request

```javascript
fetch('/api/generate', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-Campus-Request': '1'
  },
  body: JSON.stringify({})
});
```

This illustrative request starts generation when the browser has an authenticated administrator session. The actual app's `api()` helper also checks the response and handles errors. The custom header is required for all POSTs. Along with same-origin behavior and session cookie settings, it helps protect browser requests against cross-site request forgery; it is not itself a user authentication credential.

## 15. Password and session handling

`Passwords.hash()` creates a random 16-byte salt and derives a 256-bit value with PBKDF2-HMAC-SHA256 using 210,000 iterations. The encoded salt and derived value are stored together. Login derives a value from the submitted password and stored salt, then compares it using `MessageDigest.isEqual`.

Hashing/derivation is not reversible encryption. Base64 is only the text encoding used to store the salt and derived bytes; Base64 is not encryption either.

On successful login, the application replaces the current opaque session cookie and stores only its SHA-256 digest in the shared database. Sessions expire after a fixed 30 minutes. The cookie is HttpOnly with SameSite Strict, and Secure in production. Password changes revoke all of the user's sessions.

These are useful protections, not a claim that the application has undergone a full security audit or is ready for institutional production use.

## 16. What to say about limits

- Local tests use H2. Live PostgreSQL and hosting verification remain deployment tasks.
- Calendar settings, A/B weeks, holidays, approved leave, multiple exam periods and multiple invigilators are implemented.
- Holidays/leave cancel affected teaching occurrences; substitute teaching and automatic make-up classes are not included.
- Timetable drafts require another administrator's approval. Historical restoration uses current resources and must be reviewed again.
- Search is bounded; workload balancing is heuristic rather than globally optimal.
- Email/SMS/push require configured HTTPS gateways. Push uses provider-registered tokens; browser subscription registration is not included.
- CSV imports create records atomically. JSON/CSV/calendar exports are exchange snapshots, not full backups.
- Institutional sync uses a configurable gateway; a specific SIS mapping, SSO and machine API keys are not included.
- Shared chat, in-app notifications and Java sockets remain; private messaging is not included.
- Production operations, account lifecycle management and external service verification remain separate work.

## 17. A clear demonstration sequence

1. Show administrator login and briefly explain authentication and roles.
2. Show faculty, rooms, and course requirements. Explain cohort and capacity.
3. Show an unavailable faculty period.
4. Generate the timetable. Explain that all required sessions are published together.
5. Attempt a class move that conflicts with another class; show rejection and the preserved schedule.
6. Add an exam in a genuinely unused period, then allocate invigilators.
7. Open faculty login in a private browser window and demonstrate shared chat across the two sessions.
8. Publish an announcement and show account-specific notification read state.
9. Show coverage, workload, utilization, and CSV export.
10. If asked about sockets, demonstrate the optional terminal client after enabling its port.

## 18. Likely viva questions

**Why did you use Servlets?** They provide standard Java HTTP request handling and sessions, and Tomcat manages their execution.

**Why JDBC?** It gives direct database access through standard Java interfaces and demonstrates prepared statements, queries, and transaction control.

**Why embedded Tomcat?** The server is packaged with the application, so the project starts as one runnable JAR without a separately configured Tomcat installation.

**How do you prevent double-booking?** Application checks compare room, faculty, cohort, time, capacity, and availability. Transactions coordinate changes, and database constraints protect relationships and certain duplicate bookings.

**What is backtracking?** It tries a valid partial assignment, continues, and revisits earlier tentative choices if later tasks cannot be assigned.

**How do you maintain consistency?** A shared database row lock coordinates transactions, and commit/rollback makes each coordinated update atomic.

**Where is multithreading used?** Tomcat processes concurrent HTTP requests, an executor sends SSE events/heartbeats, and the TCP server uses a bounded client worker pool.

**How does chat update without a page reload?** SSE signals a committed change; frontend JavaScript then fetches the updated conversation and changes the DOM.

**Is SSE bidirectional?** Its event stream is server-to-browser. Client-to-server communication uses separate HTTP requests.

**How are passwords stored?** As a salt plus PBKDF2-derived value, rather than plaintext passwords.

**What remains after restarting?** The H2 file retains academic records, messages, notifications, and read state. Live connections and other process memory must be recreated.

**Is the schedule always optimal?** No. It seeks a valid arrangement within a bounded search, and invigilation balancing is heuristic.

**What would you improve next?** Date-aware scheduling, production database deployment and migrations, account lifecycle management, audit logs, connection pooling, private messaging, and larger-scale load/performance testing.

## 19. Suggested study order

First learn the screen workflow. Then learn the browser-to-Servlet-to-service-to-JDBC request path. Next understand `Scheduler.generate()` and `Database.transaction()`. Finally study SSE, sockets, the collections, and the Java vocabulary. Use the code examples to explain behavior rather than memorizing definitions without context.
