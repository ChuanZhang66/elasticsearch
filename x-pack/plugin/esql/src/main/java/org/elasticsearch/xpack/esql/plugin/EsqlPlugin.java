/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.esql.plugin;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsFilter;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.lucene.LuceneOperator;
import org.elasticsearch.compute.lucene.ValuesSourceReaderOperator;
import org.elasticsearch.compute.operator.AbstractPageMappingOperator;
import org.elasticsearch.compute.operator.DriverStatus;
import org.elasticsearch.compute.operator.LimitOperator;
import org.elasticsearch.compute.operator.MvExpandOperator;
import org.elasticsearch.compute.operator.exchange.ExchangeService;
import org.elasticsearch.compute.operator.exchange.ExchangeSinkOperator;
import org.elasticsearch.compute.operator.exchange.ExchangeSourceOperator;
import org.elasticsearch.compute.operator.topn.TopNOperatorStatus;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.threadpool.ExecutorBuilder;
import org.elasticsearch.threadpool.FixedExecutorBuilder;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.core.action.XPackInfoFeatureAction;
import org.elasticsearch.xpack.core.action.XPackUsageFeatureAction;
import org.elasticsearch.xpack.esql.EsqlInfoTransportAction;
import org.elasticsearch.xpack.esql.EsqlUsageTransportAction;
import org.elasticsearch.xpack.esql.action.EsqlAsyncGetResultAction;
import org.elasticsearch.xpack.esql.action.EsqlQueryAction;
import org.elasticsearch.xpack.esql.action.RestEsqlAsyncQueryAction;
import org.elasticsearch.xpack.esql.action.RestEsqlDeleteAsyncResultAction;
import org.elasticsearch.xpack.esql.action.RestEsqlGetAsyncResultAction;
import org.elasticsearch.xpack.esql.action.RestEsqlQueryAction;
import org.elasticsearch.xpack.esql.execution.PlanExecutor;
import org.elasticsearch.xpack.esql.querydsl.query.SingleValueQuery;
import org.elasticsearch.xpack.esql.type.EsqlDataTypeRegistry;
import org.elasticsearch.xpack.ql.index.IndexResolver;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class EsqlPlugin extends Plugin implements ActionPlugin {

    public static final String ESQL_THREAD_POOL_NAME = "esql";
    public static final String ESQL_WORKER_THREAD_POOL_NAME = "esql_worker";

    public static final Setting<Integer> QUERY_RESULT_TRUNCATION_MAX_SIZE = Setting.intSetting(
        "esql.query.result_truncation_max_size",
        10000,
        1,
        1000000,
        Setting.Property.NodeScope,
        Setting.Property.Dynamic
    );

    public static final Setting<Integer> QUERY_RESULT_TRUNCATION_DEFAULT_SIZE = Setting.intSetting(
        "esql.query.result_truncation_default_size",
        500,
        1,
        10000,
        Setting.Property.NodeScope,
        Setting.Property.Dynamic
    );

    @Override
    public Collection<?> createComponents(PluginServices services) {
        CircuitBreaker circuitBreaker = services.indicesService().getBigArrays().breakerService().getBreaker("request");
        Objects.requireNonNull(circuitBreaker, "request circuit breaker wasn't set");
        Settings settings = services.clusterService().getSettings();
        ByteSizeValue maxPrimitiveArrayBlockSize = settings.getAsBytesSize(
            BlockFactory.MAX_BLOCK_PRIMITIVE_ARRAY_SIZE_SETTING,
            BlockFactory.DEFAULT_MAX_BLOCK_PRIMITIVE_ARRAY_SIZE
        );
        BigArrays bigArrays = services.indicesService().getBigArrays().withCircuitBreaking();
        BlockFactory blockFactory = new BlockFactory(circuitBreaker, bigArrays, maxPrimitiveArrayBlockSize, null);
        return List.of(
            new PlanExecutor(
                new IndexResolver(
                    services.client(),
                    services.clusterService().getClusterName().value(),
                    EsqlDataTypeRegistry.INSTANCE,
                    Set::of
                )
            ),
            new ExchangeService(
                services.clusterService().getSettings(),
                services.threadPool(),
                EsqlPlugin.ESQL_THREAD_POOL_NAME,
                blockFactory
            ),
            blockFactory
        );
    }

    /**
     * The settings defined by the ESQL plugin.
     *
     * @return the settings
     */
    @Override
    public List<Setting<?>> getSettings() {
        return List.of(QUERY_RESULT_TRUNCATION_DEFAULT_SIZE, QUERY_RESULT_TRUNCATION_MAX_SIZE);
    }

    @Override
    public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
        return List.of(
            new ActionHandler<>(EsqlQueryAction.INSTANCE, TransportEsqlQueryAction.class),
            new ActionHandler<>(EsqlAsyncGetResultAction.INSTANCE, TransportEsqlAsyncGetResultsAction.class),
            new ActionHandler<>(EsqlStatsAction.INSTANCE, TransportEsqlStatsAction.class),
            new ActionHandler<>(XPackUsageFeatureAction.ESQL, EsqlUsageTransportAction.class),
            new ActionHandler<>(XPackInfoFeatureAction.ESQL, EsqlInfoTransportAction.class)
        );
    }

    @Override
    public List<RestHandler> getRestHandlers(
        Settings settings,
        RestController restController,
        ClusterSettings clusterSettings,
        IndexScopedSettings indexScopedSettings,
        SettingsFilter settingsFilter,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Supplier<DiscoveryNodes> nodesInCluster
    ) {
        return List.of(
            new RestEsqlQueryAction(),
            new RestEsqlAsyncQueryAction(),
            new RestEsqlGetAsyncResultAction(),
            new RestEsqlDeleteAsyncResultAction()
        );
    }

    @Override
    public List<NamedWriteableRegistry.Entry> getNamedWriteables() {
        return Stream.concat(
            List.of(
                DriverStatus.ENTRY,
                AbstractPageMappingOperator.Status.ENTRY,
                ExchangeSinkOperator.Status.ENTRY,
                ExchangeSourceOperator.Status.ENTRY,
                LimitOperator.Status.ENTRY,
                LuceneOperator.Status.ENTRY,
                TopNOperatorStatus.ENTRY,
                MvExpandOperator.Status.ENTRY,
                ValuesSourceReaderOperator.Status.ENTRY,
                SingleValueQuery.ENTRY
            ).stream(),
            Block.getNamedWriteables().stream()
        ).toList();
    }

    @Override
    public List<ExecutorBuilder<?>> getExecutorBuilders(Settings settings) {
        final int allocatedProcessors = EsExecutors.allocatedProcessors(settings);
        return List.of(
            new FixedExecutorBuilder(
                settings,
                ESQL_THREAD_POOL_NAME,
                allocatedProcessors,
                1000,
                ESQL_THREAD_POOL_NAME,
                EsExecutors.TaskTrackingConfig.DEFAULT
            ),
            // TODO: Maybe have two types of threadpools for workers: one for CPU-bound and one for I/O-bound tasks.
            // And we should also reduce the number of threads of the CPU-bound threadpool to allocatedProcessors.
            new FixedExecutorBuilder(
                settings,
                ESQL_WORKER_THREAD_POOL_NAME,
                ThreadPool.searchOrGetThreadPoolSize(allocatedProcessors),
                1000,
                ESQL_WORKER_THREAD_POOL_NAME,
                EsExecutors.TaskTrackingConfig.DEFAULT
            )
        );
    }
}
