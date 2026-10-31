# Academic calendar, review, delivery and exchange

This guide describes the extended application and supersedes the original weekly-only workflow in the viva guide. All six requested feature areas are implemented locally. External delivery uses configurable gateways; provider accounts and institutional mappings are configured separately.

## Run and upgrade

Build with `scripts/build.ps1` and restart the application. Back up an existing database before upgrading. Local startup adds the new tables without clearing existing records. It removes the old room/day/period unique constraint on lessons so opposite-week classes can share a slot; coordinated date-aware validation now enforces those conflicts. An existing publication is captured as a historical version on upgrade. Run only one schema upgrade at a time.

For production PostgreSQL, stop old application instances, run the new JAR with `--init-db` using the configured database credentials, then start the new version. Normal production startup does not migrate the database. This is an additive upgrade/bootstrap, not a general versioned migration framework. H2 is covered by automated tests; a live PostgreSQL deployment still needs verification. MySQL compatibility of this upgrade has not been verified.

## Calendar and teaching

Open **Academic calendar → Configure semester**. Set semester boundaries (at most 731 inclusive days), an IANA timezone such as `Asia/Kolkata`, teaching days (0=Monday, 6=Sunday), and 1–16 ordered, nonoverlapping periods in `HH:mm-HH:mm` format. Defaults are the current calendar year, Monday–Friday, and the original six periods. Changes that invalidate existing assignments are rejected.

Courses can run every week (`ALL`), in week `A`, or in week `B`. Week A starts on the Monday of the week containing the semester start; following weeks alternate. Session requirements apply to each active week. Opposite-week courses can share the same room and period. The timetable shows recurring templates; **Academic calendar → Show teaching** shows actual dated occurrences.

Administrators add holidays and record approved, inclusive faculty leave ranges. Holidays cancel all teaching on that date; leave cancels the affected faculty member's classes. These are explicit cancellations, not substitute-teacher or automatic make-up-class workflows. Existing exams cannot be placed on holidays or outside the semester. Leave that conflicts with an allocated invigilation duty is rejected. Removing leave/holidays rechecks conflicts before reinstating classes. Calendar configuration and approved leave are administrative actions, separate from timetable review.

The dashboard uses the campus timezone and actual teaching occurrences for today's classes. Room utilization counts semester occurrences rather than double-counting A/B templates.

## Examinations

**Examinations → Add examination** accepts a first period, duration in consecutive configured periods, and required invigilator count (1–20). An exam can use a non-teaching weekday within the semester. It cannot extend past the final period. If periods have gaps, the exam's displayed calendar interval spans those gaps; scheduling conflicts use the occupied period indices.

Allocation assigns distinct faculty to every duty and checks every occupied period, actual teaching occurrences, weekly unavailability, date-specific leave, and overlapping exams. The examined course's instructor is excluded. Allocation is atomic: if a full solution cannot be found, existing duties remain unchanged. Workload balancing uses occupied periods as its heuristic. The search is bounded; optimality is not guaranteed.

## Drafts, approval and history

1. Create a second administrator from **People** for independent review.
2. **Generate timetable** creates a draft without replacing the publication.
3. Open **Schedule review**, preview the draft, and select **Submit**.
4. A different administrator approves or rejects the pending draft. Authors cannot review their own drafts.
5. Publish an approved draft. Publication revalidates conflicts and complete course coverage in a transaction.

Moving a class also creates a draft. Published versions remain in history. **Restore as draft** copies an earlier assignment set into a new draft for review; it does not silently replace the current publication. Preview displays the calendar, course patterns and resource names captured with the draft. Restoration uses current calendar/resource data and may fail if historical resources were removed or requirements changed. It is not a whole-database restore.

Drafts capture their academic input context. Changes to settings, courses, rooms, leave, availability, exams or the current publication make an earlier draft stale. Generate or restore a fresh draft and review it again. The latest 100 versions appear in the UI; older versions remain stored. Exam creation/allocation and calendar administration currently remain direct administrator actions.

## Email, SMS, push and institutional gateways

In **Settings → Add or update channel**, users opt into future notifications using an email address, E.164 international telephone number, or a push token already registered with their gateway. Push is token-based through your gateway, not a built-in browser Web Push registration service. Disabling a preference affects future enqueueing; already queued messages remain queued.

Set these environment variables before starting the application (no `.env` file is automatically loaded):

| Channel | HTTPS endpoint | Bearer token |
| --- | --- | --- |
| Email | `EMAIL_GATEWAY_URL` | `EMAIL_GATEWAY_TOKEN` |
| SMS | `SMS_GATEWAY_URL` | `SMS_GATEWAY_TOKEN` |
| Push | `PUSH_GATEWAY_URL` | `PUSH_GATEWAY_TOKEN` |
| Institutional system | `INSTITUTION_GATEWAY_URL` | `INSTITUTION_GATEWAY_TOKEN` |

Gateways translate the following request into the provider or institutional system's native format. Endpoint URLs must use HTTPS; redirects are not followed. Tokens stay server-side and are never returned by the application.

```http
POST /your-configured-path
Authorization: Bearer <server-configured-token>
Content-Type: application/json
Idempotency-Key: <outbox-record-uuid>
```

```json
{
  "id": "outbox-record-uuid",
  "channel": "EMAIL",
  "destination": "member@example.edu",
  "message": "Timetable published: Generated timetable",
  "created_at": 1793448000000
}
```

The durable outbox is written in the same transaction as in-app notifications. A worker leases jobs and sends up to 20 per pass, with timeouts and exponential retries (five attempts). A successful HTTP 2xx response marks `ACCEPTED`, meaning gateway acceptance, not recipient delivery. Gateways must deduplicate `Idempotency-Key`: a crash between sending and recording success can cause a retry. Missing configuration and HTTP failures remain visible under **Imports & integrations**. Failed jobs can be retried after configuration is repaired. No live provider delivery was exercised during development; the worker's success/failure behavior is tested using an injected HTTP sender.

When configured, the institutional gateway receives campus notification events. **Queue institutional sync** explicitly queues a full secret-free academic JSON snapshot. For this job, `destination` is `institution`, and `message` contains the serialized snapshot JSON, which the gateway should parse. This push interface does not create an unrestricted inbound public endpoint.

## Bulk imports and exports

**Imports & integrations → Import CSV** offers downloadable templates for users, rooms, courses, exams, holidays and faculty leave. Files are limited to 500 rows and 900 KB in the browser, with a 1 MiB API request limit. Quoted CSV fields, embedded commas/newlines, and doubled quotes are supported. All rows succeed together or all roll back. Imports create records; they do not silently update existing records. Duplicate names/codes/emails are rejected. User imports require individual initial passwords; protect and remove those files after onboarding.

Courses and leave may reference `faculty_email`; exams may reference `course_code` and `room_name`. IDs are also supported through `faculty_id`, `course_id` and `room_id`. Period indices start at 0. Import faculty/rooms before dependent courses/exams.

Administrators can download academic JSON, an iCalendar file, and CSV files for timetable templates, dated classes, exams, directory and rooms. JSON exports omit password hashes, authentication sessions, gateway tokens, notification preferences and chat messages. They are academic exchange snapshots, not full backups. Calendar exports expand dated classes, omit holidays/leave cancellations and convert campus-local times to UTC. Calendar downloads are snapshots, not subscribed live feeds.

## API additions

All endpoints require the existing session cookie. All POSTs require `X-Campus-Request: 1`. Administrative permissions are enforced server-side. Integrators can use the login API and cookie jar with a dedicated administrator account, or receive outbound sync through the institutional gateway. Long-lived machine API keys, SSO and provider-specific SIS mappings are not included.

| Method | Path | Body / parameters |
| --- | --- | --- |
| POST | `/api/academic/settings` | `semester_start`, `semester_end`, `timezone`, `teaching_days` number array, `periods` string array |
| POST | `/api/holiday` | `holiday_date`, `label` |
| POST | `/api/leave` | `faculty_id`, `start_date`, `end_date` |
| POST | `/api/course/pattern` | `id`, `week_pattern`: `ALL`, `A`, `B` |
| POST | `/api/exams` | Existing fields plus optional `duration`, `required_staff` (default 1) |
| POST | `/api/generate`, `/api/move` | Create a generated/moved timetable draft |
| POST | `/api/schedule/submit`, `/approve`, `/reject`, `/publish`, `/restore` | Full path under `/api/schedule/`; body `{ "id": "version-id" }` |
| GET | `/api/occurrences` | Dated teaching occurrences visible to authenticated members |
| POST | `/api/delivery/preferences` | `channel`, `destination`, `enabled` (boolean); applies only to the signed-in user |
| POST | `/api/delivery/retry` | `id` of a failed outbox entry; administrator only |
| POST | `/api/imports` | `entity`, `csv` text |
| GET | `/api/export` | `format=json`, `format=ics`, or `format=csv&entity=lessons|exams|occurrences|users|rooms` |
| POST | `/api/integration/sync` | `{}`; administrator only |

## Verification

The expanded automated suite covers draft isolation, independent review, stale-draft rejection, restore-as-draft, A/B room sharing, holiday/leave cancellation, multi-period staff conflicts, atomic import failures, timezone exports, gateway retries, repeated upgrades, and the original API, scheduling and TCP chat scenarios. Live provider delivery, PostgreSQL operation and public deployment require environment-specific verification.
