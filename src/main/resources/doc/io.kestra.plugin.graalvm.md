# How to use the GraalVM plugin

Execute inline JavaScript, Python, or Ruby scripts directly within Kestra flows using GraalVM's polyglot runtime — no Docker container or external runtime needed.

Every task also accepts `options` (advanced, a map of GraalVM context option key/value pairs, e.g. `python.WarnOptions` or `js.ecmascript-version`). Keys that would weaken the sandbox already enforced by these tasks are rejected at execution time: `python.PosixModuleBackend`, any key related to host access or class loading, and any key prefixed with `engine.` or `sandbox.`.

## Tasks

### JavaScript

`js.Eval` executes a JavaScript script — set `script` (required). Optionally declare `outputs` (a list of variable names to capture from the script's scope and expose as task outputs).

`js.FileTransform` applies a JavaScript transformation to each row of an ION file — set `script` (required) and `from` (required, a `kestra://` URI). Optionally set `concurrent` (minimum 2) to process rows in parallel.

### Python

`python.Eval` executes a Python script — set `script` (required). Optionally declare `outputs` and `modules` (a map of module names to install or configure before execution).

`python.FileTransform` applies a Python transformation to each row of an ION file — set `script` (required) and `from` (required, a `kestra://` URI). Optionally set `concurrent` (minimum 2).

**Security note**: both Python tasks enable GraalVM's engine-wide native access so that C-extension-backed stdlib modules (`ssl`, `sqlite3`, `lzma`) work. Standard OS process APIs (`os.system`, `subprocess`) stay blocked regardless. However, GraalVM has no way to grant native access for its own C-extension loader while denying it to guest code that reaches native functions directly (e.g. Python's `ctypes`) — a script can therefore still execute arbitrary OS commands or manipulate process memory this way. Unlike Kestra's `Script`/`Shell` tasks, which typically run in an isolated container or process via a `TaskRunner`, this code runs inline in the worker JVM process itself, giving it direct access to the worker's own memory, file descriptors, and any secrets or other task state resident in that JVM — a materially larger blast radius than before native access was added. Only run Python scripts from users already trusted with the flow's credentials and infrastructure.

### Ruby

`ruby.Eval` executes a Ruby script — set `script` (required). Optionally declare `outputs`.

`ruby.FileTransform` applies a Ruby transformation to each row of an ION file — set `script` (required) and `from` (required, a `kestra://` URI). Optionally set `concurrent` (minimum 2).
