/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.dataprepper.plugins.ml_inference.processor;

import io.micrometer.core.instrument.Counter;
import org.opensearch.dataprepper.aws.api.AwsCredentialsSupplier;
import org.opensearch.dataprepper.expression.ExpressionEvaluator;
import org.opensearch.dataprepper.expression.ExpressionParsingException;
import org.opensearch.dataprepper.metrics.PluginMetrics;
import org.opensearch.dataprepper.model.annotations.DataPrepperPlugin;
import org.opensearch.dataprepper.model.annotations.DataPrepperPluginConstructor;
import org.opensearch.dataprepper.model.configuration.PluginSetting;
import org.opensearch.dataprepper.model.event.Event;
import org.opensearch.dataprepper.model.plugin.PluginFactory;
import org.opensearch.dataprepper.model.processor.AbstractProcessor;
import org.opensearch.dataprepper.model.processor.Processor;
import org.opensearch.dataprepper.model.record.Record;
import org.opensearch.dataprepper.plugins.ml_inference.processor.common.MLBatchJobCreator;
import org.opensearch.dataprepper.plugins.ml_inference.processor.common.MLBatchJobCreatorFactory;
import org.opensearch.dataprepper.plugins.ml_inference.processor.common.ModelSyncInferenceExecutor;
import org.opensearch.dataprepper.plugins.ml_inference.processor.configuration.ActionType;
import org.opensearch.dataprepper.plugins.ml_inference.processor.configuration.ServiceName;
import org.opensearch.dataprepper.plugins.ml_inference.processor.dlq.DlqPushHandler;
import org.opensearch.dataprepper.plugins.ml_inference.processor.exception.MLBatchJobException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static org.opensearch.dataprepper.logging.DataPrepperMarkers.NOISY;

@DataPrepperPlugin(name = "ml_inference", pluginType = Processor.class, pluginConfigurationType = MLProcessorConfig.class)
public class MLProcessor extends AbstractProcessor<Record<Event>, Record<Event>> {
    public static final Logger LOG = LoggerFactory.getLogger(MLProcessor.class);
    public static final String NUMBER_OF_ML_PROCESSOR_SUCCESS = "BatchJobRequestsSucceeded";
    public static final String NUMBER_OF_ML_PROCESSOR_FAILED = "BatchJobRequestsFailed";

    private final String whenCondition;
    private final MLBatchJobCreator mlBatchJobCreator;
    private final ModelSyncInferenceExecutor modelSyncInferenceExecutor;
    private final boolean isPredictMode;
    private final Counter numberOfMLProcessorSuccessCounter;
    private final Counter numberOfMLProcessorFailedCounter;
    private final ExpressionEvaluator expressionEvaluator;
    private final PluginSetting pluginSetting;

    private DlqPushHandler dlqPushHandler = null;

    @DataPrepperPluginConstructor
    public MLProcessor(final MLProcessorConfig mlProcessorConfig, final PluginMetrics pluginMetrics, final PluginFactory pluginFactory, final PluginSetting pluginSetting, final AwsCredentialsSupplier awsCredentialsSupplier, final ExpressionEvaluator expressionEvaluator) {
        super(pluginMetrics);
        this.whenCondition = mlProcessorConfig.getWhenCondition();
        this.numberOfMLProcessorSuccessCounter = pluginMetrics.counter(NUMBER_OF_ML_PROCESSOR_SUCCESS);
        this.numberOfMLProcessorFailedCounter = pluginMetrics.counter(NUMBER_OF_ML_PROCESSOR_FAILED);
        this.expressionEvaluator = expressionEvaluator;
        this.pluginSetting = pluginSetting;
        this.isPredictMode = ActionType.PREDICT.equals(mlProcessorConfig.getActionType());

        if (mlProcessorConfig.getDlqPluginSetting() != null) {
            this.dlqPushHandler = new DlqPushHandler(pluginFactory, pluginSetting, mlProcessorConfig.getDlq(), mlProcessorConfig.getAwsAuthenticationOptions());
        }

        if (isPredictMode) {
            this.modelSyncInferenceExecutor = new ModelSyncInferenceExecutor(mlProcessorConfig, awsCredentialsSupplier);
            this.mlBatchJobCreator = null;
        } else {
            this.modelSyncInferenceExecutor = null;
            final ServiceName serviceName = mlProcessorConfig.getServiceName();
            this.mlBatchJobCreator = MLBatchJobCreatorFactory.getJobCreator(serviceName, mlProcessorConfig, awsCredentialsSupplier, pluginMetrics, dlqPushHandler);
        }
    }

    @Override
    public Collection<Record<Event>> doExecute(Collection<Record<Event>> records) {
        final List<Record<Event>> resultRecords = new ArrayList<>();

        if (isPredictMode) {
            return executePredictMode(records, resultRecords);
        }
        return executeBatchMode(records, resultRecords);
    }

    private Collection<Record<Event>> executePredictMode(final Collection<Record<Event>> records,
                                                          final List<Record<Event>> resultRecords) {
        if (records.isEmpty()) {
            return resultRecords;
        }
        final List<Record<Event>> filteredRecords = filterByCondition(records, resultRecords);
        if (filteredRecords.isEmpty()) {
            return resultRecords;
        }
        try {
            resultRecords.addAll(modelSyncInferenceExecutor.execute(filteredRecords));
            numberOfMLProcessorSuccessCounter.increment();
        } catch (final Exception e) {
            LOG.error(NOISY, "Unexpected error during PREDICT processing: {}", e.getMessage(), e);
            numberOfMLProcessorFailedCounter.increment();
        }
        return resultRecords;
    }

    private Collection<Record<Event>> executeBatchMode(final Collection<Record<Event>> records,
                                                        final List<Record<Event>> resultRecords) {
        mlBatchJobCreator.checkAndProcessBatch();
        mlBatchJobCreator.addProcessedBatchRecordsToResults(resultRecords);

        if (records.isEmpty()) {
            return resultRecords;
        }
        final List<Record<Event>> filteredRecords = filterByCondition(records, resultRecords);
        if (filteredRecords.isEmpty()) {
            return resultRecords;
        }
        try {
            mlBatchJobCreator.createMLBatchJob(filteredRecords, resultRecords);
            numberOfMLProcessorSuccessCounter.increment();
        } catch (MLBatchJobException e) {
            LOG.error(NOISY, "ML Batch job creation failed: {}", e.getMessage());
            numberOfMLProcessorFailedCounter.increment();
        } catch (Exception e) {
            LOG.error(NOISY, "Unexpected Error occurred while creating the batch job: {}", e.getMessage(), e);
            numberOfMLProcessorFailedCounter.increment();
        }
        return resultRecords;
    }

    private List<Record<Event>> filterByCondition(final Collection<Record<Event>> records,
                                                   final List<Record<Event>> resultRecords) {
        return records.stream()
                .filter(record -> {
                    try {
                        final boolean meetCondition = whenCondition == null
                                || expressionEvaluator.evaluateConditional(whenCondition, record.getData());
                        if (!meetCondition) {
                            resultRecords.add(record);
                        }
                        return meetCondition;
                    } catch (ExpressionParsingException e) {
                        LOG.warn("Expression parsing failed for record: {}. Error: {}", record, e.getMessage());
                        resultRecords.add(record);
                        return false;
                    } catch (ClassCastException e) {
                        LOG.warn("Unexpected return type when evaluating condition for record: {}. Error: {}", record, e.getMessage());
                        resultRecords.add(record);
                        return false;
                    } catch (Exception e) {
                        LOG.error("Failed to evaluate conditional expression for record: {}", record, e);
                        resultRecords.add(record);
                        return false;
                    }
                })
                .collect(Collectors.toList());
    }

    @Override
    public void prepareForShutdown() {
        if (mlBatchJobCreator != null) {
            mlBatchJobCreator.prepareForShutdown();
        }
    }

    @Override
    public boolean isReadyForShutdown() {
        return mlBatchJobCreator == null || mlBatchJobCreator.isReadyForShutdown();
    }

    @Override
    public void shutdown() {
        if (mlBatchJobCreator != null) {
            mlBatchJobCreator.shutdown();
        }
    }
}