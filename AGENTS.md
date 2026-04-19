# Kestra GraalVM Plugin

## What

- Provides plugin components under `io.kestra.plugin.graalvm`.
- Includes classes such as `SLF4JJULHandler`, `RunContextProxy`, `LogRunnable`, `FileTransform`.

## Why

- What user problem does this solve? Teams need to run JS, Python, or Ruby code from orchestrated workflows instead of relying on manual console work, ad hoc scripts, or disconnected schedulers.
- Why would a team adopt this plugin in a workflow? It keeps GraalVM steps in the same Kestra flow as upstream preparation, approvals, retries, notifications, and downstream systems.
- What operational/business outcome does it enable? It reduces manual handoffs and fragmented tooling while improving reliability, traceability, and delivery speed for processes that depend on GraalVM.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `graalvm`

### Key Plugin Classes

- `io.kestra.plugin.graalvm.js.Eval`
- `io.kestra.plugin.graalvm.js.FileTransform`
- `io.kestra.plugin.graalvm.python.Eval`
- `io.kestra.plugin.graalvm.python.FileTransform`
- `io.kestra.plugin.graalvm.ruby.Eval`
- `io.kestra.plugin.graalvm.ruby.FileTransform`

### Project Structure

```
plugin-graalvm/
├── src/main/java/io/kestra/plugin/graalvm/ruby/
├── src/test/java/io/kestra/plugin/graalvm/ruby/
├── build.gradle
└── README.md
```

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
