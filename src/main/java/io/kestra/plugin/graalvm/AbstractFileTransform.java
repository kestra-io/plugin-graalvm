package io.kestra.plugin.graalvm;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.*;
import java.net.URI;
import java.util.List;
import java.util.function.Function;

import static io.kestra.core.utils.Rethrow.throwConsumer;
import static io.kestra.core.utils.Rethrow.throwFunction;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractFileTransform extends AbstractScript implements RunnableTask<AbstractFileTransform.Output> {
    @NotNull
    @Schema(
        title = "Source file containing rows to transform.",
        description = "Can be a Kestra internal storage URI, a map or a list."
    )
    private Property<String> from;

    @Schema(
        title = "Number of concurrent parallel transformations to execute.",
        description = "Take care that the order is **not respected** if you use parallelism."
    )
    private Property<@Min(2) Integer> concurrent;

    @SuppressWarnings("unchecked")
    protected Output run(RunContext runContext, String languageId) throws Exception {
        // temp out file
        String from = runContext.render(this.from).as(String.class).orElseThrow();
        File tempFile = runContext.workingDir().createTempFile(".ion").toFile();
        var source = generateSource(languageId, runContext);

        try (var output = new BufferedWriter(new FileWriter(tempFile), FileSerde.BUFFER_SIZE)) {
            if (from.startsWith("kestra://")) {
                try (BufferedReader inputStream = new BufferedReader(new InputStreamReader(runContext.storage().getFile(URI.create(from))), FileSerde.BUFFER_SIZE)) {
                    this.finalize(
                            runContext,
                            FileSerde.readAll(inputStream),
                            source,
                            output
                    );
                }
            } else {
                this.finalize(
                        runContext,
                        Flux.create(throwConsumer(emitter -> {
                            Object o = JacksonMapper.toObject(from);

                            if (o instanceof List) {
                                ((List<Object>) o).forEach(emitter::next);
                            } else {
                                emitter.next(o);
                            }

                            emitter.complete();
                        }), FluxSink.OverflowStrategy.BUFFER),
                        source,
                        output
                );
            }
        }

        return Output
            .builder()
            .uri(runContext.storage().putFile(tempFile))
            .build();
    }

    private void finalize(
        RunContext runContext,
        Flux<Object> flowable,
        Source scripts,
        Writer output
    ) throws IOException, IllegalVariableEvaluationException, InterruptedException {
        Thread stdOut = null;
        Thread stdErr = null;

        try (var outStream = new PipedOutputStream();
             var inStream = new PipedInputStream(outStream);
             var errStream = new PipedOutputStream();
             var inErrStream = new PipedInputStream(errStream);
             var context = buildContext(runContext, outStream, errStream)) {
            // watch for logs from output streams in separated threads
            LogRunnable stdOutRunnable = new LogRunnable(inStream, false, runContext.logger());
            LogRunnable stdErrRunnable = new LogRunnable(inErrStream, true, runContext.logger());
            stdOut = Thread.ofVirtual().name("graalvm-log-out").start(stdOutRunnable);
            stdErr = Thread.ofVirtual().name("graalvm-log-err").start(stdErrRunnable);

            Flux<Object> sequential;

            if (this.concurrent != null) {
                sequential = flowable
                        .parallel(runContext.render(this.concurrent).as(Integer.class).orElseThrow())
                        .runOn(Schedulers.boundedElastic())
                        .flatMap(this.convert(runContext, context, scripts))
                        .sequential();
            } else {
                sequential = flowable
                        .flatMap(this.convert(runContext, context, scripts));
            }

            Mono<Long> count = FileSerde.writeAll(output, sequential);

            // metrics & finalize
            Long lineCount = count.blockOptional().orElse(0L);
            runContext.metric(Counter.of("records", lineCount));
        } finally  {
            if (stdOut != null) {
                stdOut.join();
            }
            if (stdErr != null) {
                stdErr.join();
            }
        }
    }

    private Function<Object, Publisher<Object>> convert(RunContext runContext, Context context, Source scripts) {
        return throwFunction(row -> {
            var bindings = getBindings(context, scripts.getLanguage());
            // add all common vars to bindings in case of concurrency
            runContext.getVariables().forEach((key, value) -> bindings.putMember(key, value));
            bindings.putMember("runContext", runContext);
            bindings.putMember("logger", runContext.logger());
            bindings.putMember("row", row);

            var result = context.eval(scripts);
            if (result.hasMember("rows")) {
                return Flux.create(emitter -> {
                    var array = result.getMember("rows");
                    for (int i = 0; i < array.getArraySize(); i++) {
                        emitter.next(array.getArrayElement(i));
                    }
                    emitter.complete();
                });
            }

            if (bindings.hasMember("row")) {
                if (bindings.getMember("row").isNull()) {
                    return Flux.empty();
                } else {
                    return Flux.just(bindings.getMember("row").as(Object.class));
                }
            }

            return Flux.empty();
        });
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "URI of a temporary result file.",
            description = "The file will be serialized as an ION file."
        )
        private final URI uri;
    }
}
