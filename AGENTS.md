# Kestra GraalVM Plugin

## What

- Provides plugin components under `io.kestra.plugin.graalvm`.
- Includes classes such as `SLF4JJULHandler`, `RunContextProxy`, `LogRunnable`, `FileTransform`.

## Why

- This plugin integrates Kestra with GraalVM.
- It provides tasks that run JS, Python, or Ruby code via the GraalVM engine.

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
