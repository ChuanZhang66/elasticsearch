/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.admin.cluster.node.hotthreads;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.action.FailedNodeException;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.nodes.TransportNodesAction;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.monitor.jvm.HotThreads;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

public class TransportNodesHotThreadsAction extends TransportNodesAction<
    NodesHotThreadsRequest,
    NodesHotThreadsResponse,
    TransportNodesHotThreadsAction.NodeRequest,
    NodeHotThreads> {

    public static final ActionType<NodesHotThreadsResponse> TYPE = ActionType.localOnly("cluster:monitor/nodes/hot_threads");

    @Inject
    public TransportNodesHotThreadsAction(
        ThreadPool threadPool,
        ClusterService clusterService,
        TransportService transportService,
        ActionFilters actionFilters
    ) {
        super(
            TYPE.name(),
            clusterService,
            transportService,
            actionFilters,
            NodeRequest::new,
            threadPool.executor(ThreadPool.Names.GENERIC)
        );
    }

    @Override
    protected NodesHotThreadsResponse newResponse(
        NodesHotThreadsRequest request,
        List<NodeHotThreads> responses,
        List<FailedNodeException> failures
    ) {
        return new NodesHotThreadsResponse(clusterService.getClusterName(), responses, failures);
    }

    @Override
    protected NodeRequest newNodeRequest(NodesHotThreadsRequest request) {
        return new NodeRequest(request);
    }

    @Override
    protected NodeHotThreads newNodeResponse(StreamInput in, DiscoveryNode node) throws IOException {
        return new NodeHotThreads(in);
    }

    @Override
    protected NodeHotThreads nodeOperation(NodeRequest request, Task task) {
        HotThreads hotThreads = new HotThreads().busiestThreads(request.request.threads)
            .type(request.request.type)
            .sortOrder(request.request.sortOrder)
            .interval(request.request.interval)
            .threadElementsSnapshotCount(request.request.snapshots)
            .ignoreIdleThreads(request.request.ignoreIdleThreads);
        try (var writer = new StringWriter()) {
            hotThreads.detect(writer);
            return new NodeHotThreads(clusterService.localNode(), writer.toString());
        } catch (Exception e) {
            throw new ElasticsearchException("failed to detect hot threads", e);
        }
    }

    public static class NodeRequest extends TransportRequest {

        // TODO don't wrap the whole top-level request, it contains heavy and irrelevant DiscoveryNode things; see #100878
        NodesHotThreadsRequest request;

        public NodeRequest(StreamInput in) throws IOException {
            super(in);
            request = new NodesHotThreadsRequest(in);
        }

        NodeRequest(NodesHotThreadsRequest request) {
            this.request = request;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            request.writeTo(out);
        }
    }
}
