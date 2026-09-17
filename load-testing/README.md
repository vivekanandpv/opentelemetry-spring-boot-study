# Load testing for telemetry generation

[`customer-load-test.js`](customer-load-test.js) is a [k6](https://k6.io) script
that fires a randomized, weighted mix of GET/POST/PUT/DELETE calls at the Customer
API, including requests that are deliberately built to fail: bad payloads, missing
IDs, duplicate emails. The point is to give Tempo, Prometheus, and Loki something
varied and realistic to show off in Grafana. This isn't meant as a correctness
test, so the 400s, 404s, and 409s it triggers are expected, not bugs.

We picked k6 because it's built by Grafana Labs, a natural pairing for this stack,
and it runs just as well through Docker, which this project already needs anyway.
Nothing extra to install.

## Run it

With the app and the observability stack (`docker compose up -d`) both running:

```bash
docker run --rm -i \
  -e BASE_URL=http://host.docker.internal:8080 \
  -e VUS=20 -e DURATION=2m \
  grafana/k6 run - < load-testing/customer-load-test.js
```

`host.docker.internal` is how a container reaches the host's `localhost` on Mac and
Windows, right out of the box. **On Linux**, add
`--add-host=host.docker.internal:host-gateway` to the command above (needs Docker
Engine 20.10+) — without it, `host.docker.internal` won't resolve there at all.

The defaults are 20 virtual users for 2 minutes, which is comfortably thousands of
requests. Tune it with the `VUS`, `DURATION`, and `BASE_URL` environment variables:

```bash
docker run --rm -i \
  -e BASE_URL=http://host.docker.internal:8080 \
  -e VUS=50 -e DURATION=5m \
  grafana/k6 run - < load-testing/customer-load-test.js
```

## What it generates

Each iteration picks from a weighted random mix of actions (see the `actions`
array in the script if you want to change the weights):

| Action | Endpoint | Expected result |
|---|---|---|
| List | `GET /customers` | 200 |
| Get (hit) | `GET /customers/{id}` | 200 or 404 |
| Get (miss) | `GET /customers/{id}` with a bogus id | 404 |
| Create | `POST /customers` | 201 |
| Create (invalid) | `POST /customers` with bad payload | 400 |
| Create (duplicate) | `POST /customers` reusing an existing email | 409 |
| Update (hit) | `PUT /customers/{id}` | 200 or 404 |
| Update (miss) | `PUT /customers/{id}` with a bogus id | 404 |
| Delete | `DELETE /customers/{id}` | 204 or 404 |

Each virtual user remembers the IDs it's created, so the get, update, and delete
calls have real records to target instead of only ever hitting empty ones.

A note on reading k6's own summary: its `http_req_failed` metric flags any 4xx or
5xx response as a "failure," and since we're generating those on purpose, that
number will sit somewhere around 15 to 25 percent quite legitimately. Look at the
`checks` section instead — those assert the specific status code each action
expects, and should sit close to 100 percent, aside from the odd bit of
nondeterminism where a "hit" action races a concurrent delete from another VU.

## Troubleshooting: everything comes back as a check failure

If every single check fails, including ones that should trivially pass like `list:
200`, k6 almost certainly isn't reaching this app at all — it's hitting something
else that happens to be listening on the same port. This isn't a flaw in Docker or
in `host.docker.internal` itself. We ran into it once during setup, when an
unrelated Kubernetes pod, proxied by OrbStack, had already claimed `127.0.0.1:8080`
on IPv4 before the app started, so the app's own bind silently fell back to IPv6
only. Every client that resolves to IPv4, which includes k6 running through Docker,
landed on that other service instead, and got back its own generic 404 for every
path. Once we stopped that other process, `host.docker.internal` started working
correctly with no other changes needed.

To check for this yourself:

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN     # look for more than one process on :8080 (Mac/Linux)
curl -4 http://127.0.0.1:8080/api/v1/customers   # force IPv4
curl -6 http://[::1]:8080/api/v1/customers       # force IPv6
```

If those two commands give you different results, something else is squatting on
the port. Find it in the `lsof` output above and stop it — that's the real,
permanent fix, and honestly the only reliable one here, since a container's own
IPv6 loopback isn't the host's, and there's no Docker-side flag that substitutes
for actually freeing the port.

## After running

Open Grafana at `localhost:3000`, go to Explore, and take a look around:
Prometheus, where `http_server_requests_milliseconds_count` broken down `by
(status)` shows the status-code spread this generates; Tempo, where searching by
service name shows the volume and mix of traces, including the error-status ones;
and Loki, where filtering by `service_name` and following a `trace_id` back leads
you to its trace.

See [`../docs/telemetry-flow.md`](../docs/telemetry-flow.md) for how the data
actually gets there, and
[`../docs/tempo-metrics-generator.md`](../docs/tempo-metrics-generator.md) for how
Tempo turns these traces into metrics of their own.
