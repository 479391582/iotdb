/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.cluster.qp.executor;

import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.closure.ReadIndexClosure;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.option.CliOptions;
import com.alipay.sofa.jraft.rpc.impl.cli.BoltCliClientService;
import com.alipay.sofa.jraft.util.Bits;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.apache.iotdb.cluster.callback.SingleQPTask;
import org.apache.iotdb.cluster.config.ClusterConfig;
import org.apache.iotdb.cluster.entity.raft.DataPartitionRaftHolder;
import org.apache.iotdb.cluster.entity.raft.MetadataRaftHolder;
import org.apache.iotdb.cluster.entity.raft.RaftService;
import org.apache.iotdb.cluster.exception.RaftConnectionException;
import org.apache.iotdb.cluster.qp.ClusterQPExecutor;
import org.apache.iotdb.cluster.rpc.request.QueryMetadataInStringRequest;
import org.apache.iotdb.cluster.rpc.request.QueryStorageGroupRequest;
import org.apache.iotdb.cluster.rpc.request.QueryTimeSeriesRequest;
import org.apache.iotdb.cluster.rpc.response.BasicResponse;
import org.apache.iotdb.cluster.rpc.response.QueryMetadataInStringResponse;
import org.apache.iotdb.cluster.rpc.response.QueryStorageGroupResponse;
import org.apache.iotdb.cluster.rpc.response.QueryTimeSeriesResponse;
import org.apache.iotdb.cluster.utils.RaftUtils;
import org.apache.iotdb.db.exception.PathErrorException;
import org.apache.iotdb.db.exception.ProcessorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handle < show timeseries <path> > logic
 */
public class QueryMetadataExecutor extends ClusterQPExecutor {

  private static final Logger LOGGER = LoggerFactory.getLogger(QueryMetadataExecutor.class);

  /**
   * Template of <show timeseries <storage group>>
   */
  private static final String SHOW_TIMESERIES_TEMPLETE = "show timeseries %s";

  public QueryMetadataExecutor() {

  }

  public void init() {
    this.cliClientService = new BoltCliClientService();
    this.cliClientService.init(new CliOptions());
    this.subTaskNum = 1;
  }

  public Set<String> processStorageGroupQuery() throws InterruptedException {
    return queryStorageGroupLocally();
  }

  /**
   * Handle show timeseries <path> statement
   */
  public List<List<String>> processTimeSeriesQuery(String path)
      throws InterruptedException, PathErrorException, ProcessorException {
    List<List<String>> res = new ArrayList<>();
    /** Check whether it's related to one storage group **/
    if (checkStorageExistOfPath(path)) {
      String storageGroup = mManager.getFileNameByPath(path);
      String groupId = getGroupIdBySG(storageGroup);
      List<String> paths = new ArrayList<>();
      paths.add(path);
      handleTimseriesQuery(groupId, paths, res);
    } else {
      List<String> storageGroupList = getAllStroageGroupsByPath(path);
      Map<String, Set<String>> groupIdSGMap = classifySGByGroupId(storageGroupList);
      for (Entry<String, Set<String>> entry : groupIdSGMap.entrySet()) {
        List<String> paths = new ArrayList<>();
        for (String storageGroup : entry.getValue()) {
          paths.add(String.format(SHOW_TIMESERIES_TEMPLETE, storageGroup));
        }
        String groupId = entry.getKey();
        handleTimseriesQuery(groupId, paths, res);
      }
    }
    return res;
  }

  /**
   * Handle query timeseries in one data group
   *
   * @param groupId data group id
   */
  private void handleTimseriesQuery(String groupId, List<String> pathList, List<List<String>> res)
      throws ProcessorException, InterruptedException {
    QueryTimeSeriesRequest request = new QueryTimeSeriesRequest(groupId, pathList);
    SingleQPTask task = new SingleQPTask(false, request);

    LOGGER.info("Execute show timeseries {} statement for group {}.", pathList, groupId);
    /** Check if the plan can be executed locally. **/
    if (canHandleQueryByGroupId(groupId)) {
      LOGGER.info("Execute show timeseries {} statement locally for group {}.", pathList, groupId);
      res.addAll(queryTimeSeriesLocally(pathList, groupId, task));
    } else {
      try {
        PeerId holder = RaftUtils.getRandomPeerID(groupId);
        res.addAll(queryTimeSeries(task, holder));
      } catch (RaftConnectionException e) {
        LOGGER.error(e.getMessage());
        throw new ProcessorException("Raft connection occurs error.", e);
      }
    }
  }

  public String processMetadataInStringQuery()
      throws InterruptedException, ProcessorException {
    Set<String> groupIdSet = router.getAllGroupId();

    List<String> metadataList = new ArrayList<>(groupIdSet.size());
    List<SingleQPTask> taskList = new ArrayList<>();
    for (String groupId : groupIdSet) {
      QueryMetadataInStringRequest request = new QueryMetadataInStringRequest(groupId);
      SingleQPTask task = new SingleQPTask(false, request);
      taskList.add(task);

      LOGGER.info("Execute show metadata in string statement for group {}.", groupId);
      /** Check if the plan can be executed locally. **/
      if (canHandleQueryByGroupId(groupId)) {
        LOGGER.info("Execute show metadata in string statement locally for group {}.", groupId);
        asyncQueryMetadataInStringLocally(groupId, task);
      } else {
        try {
          PeerId holder = RaftUtils.getRandomPeerID(groupId);
          asyncSendTask(task, holder, 0);
        } catch (RaftConnectionException e) {
          LOGGER.error(e.getMessage());
          throw new ProcessorException("Raft connection occurs error.", e);
        }
      }
    }
    for (int i = 0; i < taskList.size(); i++) {
      SingleQPTask task = taskList.get(i);
      task.await();
      QueryMetadataInStringResponse response = (QueryMetadataInStringResponse) task.getResponse();
      if (!response.isSuccess()) {
        LOGGER.error("Execute show timeseries statement false.");
        throw new ProcessorException();
      }
      return ((QueryMetadataInStringResponse) task.getResponse()).getMetadata();
    }
    return combineMetadataInStringList(metadataList);
  }

  /**
   * Handle "show timeseries <path>" statement
   *
   * @param pathList column path
   */
  private List<List<String>> queryTimeSeriesLocally(List<String> pathList, String groupId, SingleQPTask task)
      throws InterruptedException, ProcessorException {
    final byte[] reqContext = new byte[4];
    Bits.putInt(reqContext, 0, requestId.incrementAndGet());
    DataPartitionRaftHolder dataPartitionHolder = (DataPartitionRaftHolder) server
        .getDataPartitionHolder(groupId);
    ((RaftService) dataPartitionHolder.getService()).getNode()
        .readIndex(reqContext, new ReadIndexClosure() {

          @Override
          public void run(Status status, long index, byte[] reqCtx) {
            QueryTimeSeriesResponse response = QueryTimeSeriesResponse.createEmptyInstance(groupId);
            if (status.isOk()) {
              try {
                LOGGER.info("start to read");
                for(String path:pathList){
                  response.addTimeSeries(dataPartitionHolder.getFsm().getShowTimeseriesPath(path));
                }
              } catch (final PathErrorException e) {
                response = QueryTimeSeriesResponse.createErrorInstance(groupId, e.toString());
              }
            } else {
              response = QueryTimeSeriesResponse.createErrorInstance(groupId, status.getErrorMsg());
            }
            task.run(response);
          }
        });
    task.await();
    QueryTimeSeriesResponse response = (QueryTimeSeriesResponse) task.getResponse();
    if (!response.isSuccess()) {
      LOGGER.error("Execute show timeseries {} statement false.", pathList);
      throw new ProcessorException();
    }
    return ((QueryTimeSeriesResponse) task.getResponse()).getTimeSeries();
  }

  private List<List<String>> queryTimeSeries(SingleQPTask task, PeerId leader)
      throws InterruptedException, RaftConnectionException {
    BasicResponse response = asyncHandleTaskGetRes(task, leader, 0);
    return ((QueryTimeSeriesResponse) response).getTimeSeries();
  }

  /**
   * Handle "show storage group" statement locally
   *
   * @return Set of storage group name
   */
  private Set<String> queryStorageGroupLocally() throws InterruptedException {
    final byte[] reqContext = new byte[4];
    Bits.putInt(reqContext, 0, requestId.incrementAndGet());
    QueryStorageGroupRequest request = new QueryStorageGroupRequest(
        ClusterConfig.METADATA_GROUP_ID);
    SingleQPTask task = new SingleQPTask(false, request);
    MetadataRaftHolder metadataHolder = (MetadataRaftHolder) server.getMetadataHolder();
    ((RaftService) metadataHolder.getService()).getNode()
        .readIndex(reqContext, new ReadIndexClosure() {

          @Override
          public void run(Status status, long index, byte[] reqCtx) {
            QueryStorageGroupResponse response;
            if (status.isOk()) {
              try {
                response = QueryStorageGroupResponse
                    .createSuccessInstance(metadataHolder.getFsm().getAllStorageGroups());
              } catch (final PathErrorException e) {
                response = QueryStorageGroupResponse.createErrorInstance(e.toString());
              }
            } else {
              response = QueryStorageGroupResponse.createErrorInstance(status.getErrorMsg());
            }
            task.run(response);
          }
        });
    task.await();
    return ((QueryStorageGroupResponse) task.getResponse()).getStorageGroups();
  }

  /**
   * Handle "show timeseries" statement
   */
  private void asyncQueryMetadataInStringLocally(String groupId, SingleQPTask task) {
    final byte[] reqContext = new byte[4];
    Bits.putInt(reqContext, 0, requestId.incrementAndGet());
    DataPartitionRaftHolder dataPartitionHolder = (DataPartitionRaftHolder) server
        .getDataPartitionHolder(groupId);
    ((RaftService) dataPartitionHolder.getService()).getNode()
        .readIndex(reqContext, new ReadIndexClosure() {

          @Override
          public void run(Status status, long index, byte[] reqCtx) {
            QueryMetadataInStringResponse response;
            if (status.isOk()) {
              LOGGER.info("start to read");
              response = new QueryMetadataInStringResponse(groupId, false,
                  dataPartitionHolder.getFsm().getMetadataInString());
              response.addResult(true);
            } else {
              response = new QueryMetadataInStringResponse(groupId, false, null, null);
              response.addResult(false);
            }
            task.run(response);
          }
        });
  }

  private String queryMetadataInString(SingleQPTask task, PeerId leader)
      throws InterruptedException, RaftConnectionException {
    BasicResponse response = asyncHandleTaskGetRes(task, leader, 0);
    return ((QueryMetadataInStringResponse) response).getMetadata();
  }

  /**
   * Combine multiple metadata in String format into single String
   *
   * @return single String of all metadata
   */
  private String combineMetadataInStringList(List<String> metadataList) {
    return null; //TODO
  }
}
