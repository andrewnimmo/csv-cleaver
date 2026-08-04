# The local service

CSV Cleaver can expose what the window does over HTTP, so that a script, a
scheduled job or another application can split files without anyone clicking
anything.

It is **off unless you ask for it**, it listens on **this machine only**, and
every call needs a **token**. Read [the security section](SECURITY.md#the-optional-http-service)
before deciding to use it — particularly the part about `--api-input path`.

---

## Starting it

```bash
csv-cleaver --api
```

The window opens as usual and the service starts alongside it. To run with no
window at all — on a server, or from a launch agent:

```bash
csv-cleaver --api --headless
```

Either way it prints something like:

```
CSV Cleaver service listening on http://127.0.0.1:8377
  Documentation:  http://127.0.0.1:8377/api-docs/index.html
  Token:          kQ8vN2rT_pXm4LzYbA7wJfDc

  Input mode:     path

  Every /api call needs:  Authorization: Bearer <token>
  Reachable from this machine only.

  With --api-input path, anyone holding this token can ask the application to
  read any file you can read. Treat it as a password. Use --api-input upload
  or none to narrow that.
```

### Options

| Option | Default | What it does |
|---|---|---|
| `--api` | off | Start the service. Nothing listens without this. |
| `--api-port PORT` | `8377` | Port, between 1024 and 65535. |
| `--api-input MODE` | `path` | `none`, `path`, `upload` or `both`. See below. |
| `--api-token TOKEN` | generated | Use a token you already have, e.g. from a secrets file. Omit it and a strong one is generated and printed. |
| `--headless` | off | Open no window. Only meaningful with `--api`; on its own it is refused, because it would start nothing at all. |

### Input modes

This is the decision worth making deliberately.

| Mode | Name a local path | Upload bytes | Results come back |
|---|---|---|---|
| `none` | ✗ | ✗ | — |
| `path` | ✓ | ✗ | written to the folder you name |
| `upload` | ✗ | ✓ | as a zip from `/api/splits/{id}/archive` |
| `both` | ✓ | ✓ | either |

`path` is the default because it is what the application is for: it reads the
file where it lies and copies nothing, so a 40 GB file costs nothing extra. The
price is that a caller holding the token can name **any** file the user can
read. `upload` gives that up in exchange for the service never touching a path
you did not send it.

A caller does not have to guess — `GET /api/capabilities` says which mode is in
force.

---

## Trying it

Open **http://127.0.0.1:8377/api-docs/index.html** in a browser. That is a
Swagger UI over the service's own OpenAPI description: press **Authorize**,
paste the token, and every endpoint below can be run from the page, including
file uploads.

The description itself is at `/api/openapi.json` and is the one part of `/api`
readable without the token — the browser fetches it with no way to add a header,
and it says nothing about the machine.

---

## Endpoints

Everything below needs `Authorization: Bearer <token>`.

### `GET /api/health`

```json
{"status": "ok"}
```

### `GET /api/capabilities`

What this service was started with. Ask this first if you are writing something
that has to work against more than one configuration.

```json
{
  "name": "CSV Cleaver",
  "version": "2.0.0 (2233632)",
  "inputMode": "path",
  "acceptsPath": true,
  "acceptsUpload": false,
  "maxUploadBytes": 268435456,
  "excelRowLimit": 1048576,
  "runningJobs": 0
}
```

### `POST /api/surveys`

Read a file once and describe it. **Writes nothing.**

```bash
curl -s http://127.0.0.1:8377/api/surveys \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"file": "/Users/you/data/orders.csv"}'
```

```json
{
  "file": "/Users/you/data/orders.csv",
  "bytes": 4823910,
  "records": 120451,
  "fields": 8,
  "delimiter": ",",
  "encoding": "UTF-8",
  "encodingBasis": "utf-8",
  "tabular": true,
  "healthy": true,
  "damage": {"ragged": 0, "strayQuote": 0, "unterminatedQuote": 0},
  "header": {"verdict": "header", "score": 0.91, "likely": true},
  "firstRows": [["id", "date", "..."], ["1", "2026-01-04", "..."]]
}
```

`header.verdict` is `header`, `unsure` or `data`. `tabular` is false for a file
with one column — a letter or a log, structurally faultless and not a table.
`healthy` false means something in the file did not parse cleanly; `damage` says
what.

### `POST /api/plans`

What a split would do. **Touches no disk.** Same body as `/api/splits`; call it
first if you want to show someone what is about to happen, or to check that
there is room.

```json
{
  "fileCount": 3,
  "exact": true,
  "dataRows": 120450,
  "rowsPerFile": 50000,
  "lastFileRows": 20450,
  "requiredBytes": 4831000,
  "freeBytes": 763730808832
}
```

A plan that cannot work carries a `problem` and one that can but perhaps should
not carries a `warning`:

```json
{"problem": {"code": "problem/not-enough-space",
             "message": "Not enough room: this needs 42.1 GB and there is 12.0 GB free."}}
```

Branch on `code`; log `message`. The service answers in English whatever
language the window is in — a caller is a program, and a string that changes
with the user's locale is not something to match on.

### `POST /api/splits`

Start a split. Answers **202** at once with a job, because splitting a file
large enough to be worth splitting takes minutes.

```bash
curl -s http://127.0.0.1:8377/api/splits \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"file": "/Users/you/data/orders.csv", "value": 50000}'
```

```json
{"id": "b0d4…", "state": "running", "startedAt": 1785817265859}
```

Answers **409** instead if the plan has a problem — running out of room, or
settings that would produce a single file. A job that dies immediately is a
worse answer than a refusal: you would have to poll to discover a mistake you
made in the request.

**Request body**

| Field | Default | Meaning |
|---|---|---|
| `file` | — | Path to the file (`path`/`both` modes only) |
| `value` | — | Rows per file, or bytes per file when `mode` is `size` |
| `mode` | `rows` | `rows` or `size` |
| `delimiter` | detected | One character |
| `hasHeader` | detected | Whether the first row names the columns |
| `includeHeader` | `true` | Repeat that row into every output file |
| `excelSafe` | `true` | When splitting by size, also roll over at Excel's row limit |
| `outDir` | a new folder beside the input, named after it | Where to write |
| `template` | `{name}_{index}` | Output name pattern |

Every default is the one the window uses, so sending nothing but a file and a
row count gets exactly what a person would get by dropping that file on the
window and pressing Split.

**Uploading instead**, when the mode allows it — the same fields as form parts:

```bash
curl -s http://127.0.0.1:8377/api/splits \
  -H "Authorization: Bearer $TOKEN" \
  -F file=@orders.csv \
  -F value=50000
```

An uploaded file has nowhere obvious to put its results and, in `upload` mode,
you are not allowed to name a folder — so `outDir` is refused and the job comes
back with an `archive` link instead.

### `GET /api/splits/{id}`

```json
{
  "id": "b0d4…",
  "state": "running",
  "startedAt": 1785817265859,
  "progress": {"rowsDone": 80000, "filesDone": 1, "currentFile": "orders_0002.csv"}
}
```

`state` is `running`, `finished`, `cancelled` or `failed`. Once it is not
running:

```json
{
  "state": "finished",
  "finishedAt": 1785817268112,
  "result": {
    "files": ["/Users/you/data/orders split/orders_0001.csv", "…"],
    "rows": 120450,
    "elapsedMs": 2253,
    "cancelled": false,
    "trashed": [],
    "leftBehind": []
  }
}
```

A finished job stays readable for **30 minutes**, so a slow poller still sees the
outcome, then is forgotten.

### `DELETE /api/splits/{id}`

Ask a running split to stop. What is left on disk is always a whole number of
complete files: the one being written is closed and removed.

### `GET /api/splits/{id}/archive`

Every file the job produced, as one zip, written to the response as it is read.
The only way to get at the results of an uploaded file. Available for a split of
a local path too, though the files are already in the folder you named.

---

## Things worth knowing

**Nothing already on disk is ever replaced.** This is the application's central
promise and the service keeps it. A split whose output name is already taken
stops rather than overwriting anything; `trashed` and `leftBehind` in the result
say what happened to files the run had to move aside.

**A survey is a full read.** It counts every record. On a very large file it
takes as long as reading the file takes, because that is what it is.

**`/api/splits` surveys the file again.** Calling `/api/surveys`, `/api/plans`
and `/api/splits` in turn reads the file three times. If that matters, call only
`/api/splits`.

**Timestamps are milliseconds since the epoch**, from this machine's clock.

**Job ids are not secrets** — but the token is, and nothing is reachable without
it.

---

## A whole run, in shell

```bash
TOKEN=…                      # printed at startup
BASE=http://127.0.0.1:8377/api
AUTH="Authorization: Bearer $TOKEN"

# What are we allowed to do?
curl -s -H "$AUTH" $BASE/capabilities

# What is in the file?
curl -s -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"file":"/data/orders.csv"}' $BASE/surveys

# Start it
ID=$(curl -s -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"file":"/data/orders.csv","value":50000,"outDir":"/data/split"}' \
  $BASE/splits | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')

# Wait
while [ "$(curl -s -H "$AUTH" $BASE/splits/$ID \
           | python3 -c 'import json,sys; print(json.load(sys.stdin)["state"])')" = running ]; do
  sleep 2
done

curl -s -H "$AUTH" $BASE/splits/$ID
```
