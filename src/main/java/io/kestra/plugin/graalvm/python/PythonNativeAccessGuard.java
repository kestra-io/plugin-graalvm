package io.kestra.plugin.graalvm.python;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;

/**
 * Closes the most direct native-FFI escape hatch left open by {@code allowNativeAccess(true)}, which
 * GraalPy requires unconditionally to run any C-extension-backed stdlib module (ssl, sqlite3, lzma...).
 * <p>
 * There is no GraalVM API to grant native access to GraalVM's own bundled C-extension loader while
 * denying it to guest-script code: {@code SandboxPolicy} is a coarse, build-time compatibility check
 * (not a runtime restriction), and GraalPy's {@code python.NativeModules} option only selects the
 * LLVM-bitcode C-extension backend, still refusing to run any C extension at all when native access is
 * disallowed. Poisoning {@code ctypes}/{@code _ctypes} in {@code sys.modules} blocks the direct,
 * single-line shell-out via {@code ctypes.CDLL(...).system(...)} (or reading/writing arbitrary process
 * memory) while leaving CAPI-backed stdlib C extensions untouched, since those load through GraalPy's
 * own extension loader rather than through {@code ctypes}.
 * <p>
 * This is defense-in-depth, not a full sandbox: because {@code allowIO(IOAccess.ALL)} already lets a
 * script write files, a sufficiently determined script could still write a native shared library to disk
 * and load it directly as a Python C-extension module via {@code importlib.machinery.ExtensionFileLoader}
 * with an explicit path, bypassing {@code ctypes} entirely -- GraalVM's native access is an all-or-nothing
 * engine-level switch and there is no way to distinguish "GraalVM's own bundled extension" loading from
 * "script-authored extension" loading at that layer. Tasks that need Python C-extension support are
 * therefore only appropriate for scripts written by users already trusted with the credentials and
 * infrastructure their flow has access to -- the same trust level Kestra assumes for any script task --
 * not for running arbitrary, adversarial, untrusted input as code.
 */
final class PythonNativeAccessGuard {
    private static final String LOCKDOWN_SCRIPT = """
        def __kestra_block_native_ffi():
            import sys
            for module_name in ("ctypes", "_ctypes"):
                sys.modules[module_name] = None
        __kestra_block_native_ffi()
        del __kestra_block_native_ffi
        """;

    private PythonNativeAccessGuard() {
    }

    static void blockDirectFFI(Context context) {
        context.eval(Source.create("python", LOCKDOWN_SCRIPT));
    }
}
