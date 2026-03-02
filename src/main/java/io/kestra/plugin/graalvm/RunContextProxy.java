package io.kestra.plugin.graalvm;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.Plugin;
import io.kestra.core.models.executions.AbstractMetricEntry;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.*;
import io.kestra.core.storages.Storage;
import io.kestra.core.storages.kv.KVStore;
import org.slf4j.Logger;

import java.net.URI;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class RunContextProxy extends RunContext {
    private final RunContext delegate;

    public RunContextProxy(RunContext delegate) {
        this.delegate = delegate;
    }


    @Override
    public String getTriggerExecutionId() {
        return delegate.getTriggerExecutionId();
    }

    @Override
    public Map<String, Object> getVariables() {
        return delegate.getVariables();
    }

    @Override
    public List<String> getSecretInputs() {
        return delegate.getSecretInputs();
    }

    @Override
    public String getTraceParent() {
        return delegate.getTraceParent();
    }

    @Override
    public void setTraceParent(String traceParent) {
        delegate.setTraceParent(traceParent);
    }

    @Override
    public String render(String inline) throws IllegalVariableEvaluationException {
        return delegate.render(inline);
    }

    @Override
    public Object renderTyped(String inline) throws IllegalVariableEvaluationException {
        return delegate.renderTyped(inline);
    }

    @Override
    public String render(String inline, Map<String, Object> variables) throws IllegalVariableEvaluationException {
        return delegate.render(inline, variables);
    }

    @Override
    public <T> RunContextProperty<T> render(Property<T> inline) {
        return delegate.render(inline);
    }

    @Override
    public List<String> render(List<String> inline) throws IllegalVariableEvaluationException {
        return delegate.render(inline);
    }

    @Override
    public List<String> render(List<String> inline, Map<String, Object> variables) throws IllegalVariableEvaluationException {
        return delegate.render(inline, variables);
    }

    @Override
    public Set<String> render(Set<String> inline) throws IllegalVariableEvaluationException {
        return delegate.render(inline);
    }

    @Override
    public Set<String> render(Set<String> inline, Map<String, Object> variables) throws IllegalVariableEvaluationException {
        return delegate.render(inline, variables);
    }

    @Override
    public Map<String, Object> render(Map<String, Object> inline) throws IllegalVariableEvaluationException {
        return delegate.render(inline);
    }

    @Override
    public Map<String, Object> render(Map<String, Object> inline, Map<String, Object> variables) throws IllegalVariableEvaluationException {
        return delegate.render(inline, variables);
    }

    @Override
    public Map<String, String> renderMap(Map<String, String> inline) throws IllegalVariableEvaluationException {
        return delegate.renderMap(inline);
    }

    @Override
    public Map<String, String> renderMap(Map<String, String> inline, Map<String, Object> variables) throws IllegalVariableEvaluationException {
        return delegate.renderMap(inline, variables);
    }

    @Override
    public <T> void validate(T bean) {
        delegate.validate(bean);
    }

    @Override
    public String decrypt(String encrypted) throws GeneralSecurityException {
        return delegate.decrypt(encrypted);
    }

    @Override
    public String encrypt(String plaintext) throws GeneralSecurityException {
        return delegate.encrypt(plaintext);
    }

    @Override
    public Logger logger() {
        return delegate.logger();
    }

    @Override
    public URI logFileURI() {
        return delegate.logFileURI();
    }

    @Override
    public URI getStorageOutputPrefix() {
        return delegate.getStorageOutputPrefix();
    }

    @Override
    public Storage storage() {
        return delegate.storage();
    }

    @Override
    public List<AbstractMetricEntry<?>> metrics() {
        return delegate.metrics();
    }

    @Override
    public <T> RunContext metric(AbstractMetricEntry<T> metricEntry) {
        return delegate.metric(metricEntry);
    }

    @Override
    public void dynamicWorkerResult(List<WorkerTaskResult> workerTaskResults) {
        delegate.dynamicWorkerResult(workerTaskResults);
    }

    @Override
    public List<WorkerTaskResult> dynamicWorkerResults() {
        return delegate.dynamicWorkerResults();
    }

    @Override
    public WorkingDir workingDir() {
        return delegate.workingDir();
    }

    @Override
    public void cleanup() {
        delegate.cleanup();
    }

    @Override
    public String tenantId() {
        return delegate.tenantId();
    }

    @Override
    public TaskRunInfo taskRunInfo() {
        return delegate.taskRunInfo();
    }

    @Override
    public FlowInfo flowInfo() {
        return delegate.flowInfo();
    }

    @Override
    public <T> Optional<T> pluginConfiguration(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, Object> pluginConfigurations() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String version() {
        return delegate.version();
    }

    @Override
    public KVStore namespaceKv(String namespace) {
        return delegate.namespaceKv(namespace);
    }

    @Override
    public LocalPath localPath() {
        return delegate.localPath();
    }

    @Override
    public boolean isInitialized() {
        return delegate.isInitialized();
    }

    @Override
    public AclChecker acl() {
        return delegate.acl();
    }

    @Override
    public AssetEmitter assets() throws IllegalVariableEvaluationException {
        return delegate.assets();
    }

    @Override
    public RunContext cloneForPlugin(Plugin plugin) {
        throw new UnsupportedOperationException();
    }

    @Override
    public InputAndOutput inputAndOutput() {
        return delegate.inputAndOutput();
    }

    @Override
    public SDK sdk() {
        throw new UnsupportedOperationException();
    }
}
