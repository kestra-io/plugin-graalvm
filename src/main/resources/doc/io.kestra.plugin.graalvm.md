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

### Ruby

`ruby.Eval` executes a Ruby script — set `script` (required). Optionally declare `outputs`.

`ruby.FileTransform` applies a Ruby transformation to each row of an ION file — set `script` (required) and `from` (required, a `kestra://` URI). Optionally set `concurrent` (minimum 2).
