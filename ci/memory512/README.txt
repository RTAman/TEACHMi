TEACHMi Linux 512MiB / 0.1 CPU verification

This is an opt-in test package for a disposable GitHub-hosted Ubuntu 24.04 runner.
It is not a Render deployment, a production memory profile, or an actual Gemini test.
Application source, APIs, Flyway, cache keys and the existing 2GiB deployment package are unchanged.

The workflow builds the backend bootJar and its existing non-root runtime image.
Only the app receives a 512MiB memory limit, equal memory+swap limit (no swap),
and 0.1 CPU. PostgreSQL and the synthetic HTTP driver have separate budgets.
It checks Docker settings and the kernel's cgroup v2 memory.max, memory.swap.max,
cpu.max, OOM counters, throttling and observed memory.peak.

Application and database publish no host ports. Both Docker networks are internal.
Gemini is false with an empty key; no secret or original lecture is provided.
All PDF/PPT/PPTX files are generated from a fixed synthetic accounting paragraph.
The large PDF contains 24 pages, images and text, below the current 20MiB limit.
The test exercises small file parsing, a large single upload and two concurrent
large uploads, authentication/CSRF/ownership/idempotency, selected concepts,
session creation, AI-disabled handling, relogin and restart restoration.

Synthetic uncertain/cache/pending-answer fixtures are inserted only into the
brand-new test database after the HTTP journey. Their checksums, source IDs and
original file checksums must remain identical after restart. This is storage
preservation verification, not actual AI content or report generation.

The HTTP driver sends test cookies explicitly on an internal HTTP network.
Secure/HttpOnly/SameSite flags are checked; browser HTTPS behavior is not.
Automatic app restarts are disabled so memory failures cannot be masked.
Cleanup verifies the newly generated UUID project labels; it cannot name an
existing production project. Never run against an existing Docker host manually.

Run through the Backend Linux 512MiB workflow. Only result.json is uploaded for
three days. It contains resource counters, statuses and fixed assertion labels.
Raw cookies, passwords, source files, DB dumps, provider responses and process
logs are not artifacts. Existing local DBs/Docker are never started by this job.

Local harness safety checks (do not start containers):
python3 -m unittest discover -s ci/memory512 -p test_safety.py -v

The workflow only runs when these verification files change. Backend business
changes still use the original regression/contract/package CI. Passing this
test is not approval to move a production backend to 512MiB; real Gemini,
complex documents, many-user/long-duration load, remote PostgreSQL/TLS and a
frontend/browser flow require their own verification and approval.
