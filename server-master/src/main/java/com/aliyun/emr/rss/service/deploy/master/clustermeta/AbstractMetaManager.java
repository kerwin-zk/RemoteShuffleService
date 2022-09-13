/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.aliyun.emr.rss.service.deploy.master.clustermeta;

import static com.aliyun.emr.rss.common.protocol.RpcNameConstants.WORKER_EP;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aliyun.emr.rss.common.RssConf;
import com.aliyun.emr.rss.common.meta.DiskInfo;
import com.aliyun.emr.rss.common.meta.WorkerInfo;
import com.aliyun.emr.rss.common.rpc.RpcAddress;
import com.aliyun.emr.rss.common.rpc.RpcEnv;
import com.aliyun.emr.rss.common.util.Utils;

public abstract class AbstractMetaManager implements IMetadataHandler {
  private static final Logger LOG = LoggerFactory.getLogger(AbstractMetaManager.class);

  // Meta data for master service
  public final Set<String> registeredShuffle = ConcurrentHashMap.newKeySet();
  public final Set<String> hostnameSet = ConcurrentHashMap.newKeySet();
  public final ArrayList<WorkerInfo> workers = new ArrayList<>();
  public final ConcurrentHashMap<String, Long> appHeartbeatTime = new ConcurrentHashMap<>();
  // blacklist
  public final Set<WorkerInfo> blacklist = ConcurrentHashMap.newKeySet();
  // workerLost events
  public final Set<WorkerInfo> workerLostEvents = ConcurrentHashMap.newKeySet();

  protected RpcEnv rpcEnv;
  protected RssConf conf;

  public long defaultPartitionSize;
  public long estimatedPartitionSize;
  public final LongAdder partitionTotalWritten = new LongAdder();
  public final LongAdder partitionTotalFileCount = new LongAdder();

  public void updateRequestSlotsMeta(
      String shuffleKey, String hostName, Map<String, Map<String, Integer>> workerWithAllocations) {
    registeredShuffle.add(shuffleKey);

    String appId = Utils.splitShuffleKey(shuffleKey)._1;
    appHeartbeatTime.compute(
        appId,
        (applicationId, oldTimestamp) -> {
          long oldTime = System.currentTimeMillis();
          if (oldTimestamp != null) {
            oldTime = oldTimestamp;
          }
          return Math.max(System.currentTimeMillis(), oldTime);
        });

    if (hostName != null) {
      hostnameSet.add(hostName);
    }
    if (!workerWithAllocations.isEmpty()) {
      synchronized (workers) {
        for (WorkerInfo workerInfo : workers) {
          String workerUniqueId = workerInfo.toUniqueId();
          if (workerWithAllocations.containsKey(workerUniqueId)) {
            workerInfo.allocateSlots(shuffleKey, workerWithAllocations.get(workerUniqueId));
          }
        }
      }
    }
  }

  public void updateReleaseSlotsMeta(String shuffleKey) {
    updateReleaseSlotsMeta(shuffleKey, null, null);
  }

  public void updateReleaseSlotsMeta(
      String shuffleKey, List<String> workerIds, List<Map<String, Integer>> slots) {
    if (workerIds != null && !workerIds.isEmpty()) {
      for (int i = 0; i < workerIds.size(); i++) {
        String workerId = workerIds.get(i);
        WorkerInfo worker = WorkerInfo.fromUniqueId(workerId);
        for (WorkerInfo w : workers) {
          if (w.equals(worker)) {
            Map<String, Integer> slotToRelease = slots.get(i);
            LOG.info("release slots for worker " + w + ", to release: " + slotToRelease);
            w.releaseSlots(shuffleKey, slotToRelease);
          }
        }
      }
    } else {
      workers.forEach(workerInfo -> workerInfo.releaseSlots(shuffleKey));
    }
  }

  public void updateUnregisterShuffleMeta(String shuffleKey) {
    registeredShuffle.remove(shuffleKey);
  }

  public void updateAppHeartbeatMeta(String appId, long time, long totalWritten, long fileCount) {
    appHeartbeatTime.put(appId, time);
    partitionTotalWritten.add(totalWritten);
    partitionTotalFileCount.add(fileCount);
  }

  public void updateAppLostMeta(String appId) {
    registeredShuffle.stream()
        .filter(shuffle -> shuffle.startsWith(appId))
        .forEach(this::updateReleaseSlotsMeta);
    registeredShuffle.removeIf(shuffleKey -> shuffleKey.startsWith(appId));
    appHeartbeatTime.remove(appId);
  }

  public void updateWorkerLostMeta(
      String host, int rpcPort, int pushPort, int fetchPort, int replicatePort) {
    WorkerInfo worker = new WorkerInfo(host, rpcPort, pushPort, fetchPort, replicatePort, null);
    workerLostEvents.add(worker);
    // remove worker from workers
    synchronized (workers) {
      workers.remove(worker);
    }
    // delete from blacklist
    blacklist.remove(worker);
    workerLostEvents.remove(worker);
  }

  public void updateWorkerRemoveMeta(
      String host, int rpcPort, int pushPort, int fetchPort, int replicatePort) {
    WorkerInfo worker = new WorkerInfo(host, rpcPort, pushPort, fetchPort, replicatePort, null);
    // remove worker from workers
    synchronized (workers) {
      workers.remove(worker);
    }
    // delete from blacklist
    blacklist.remove(worker);
  }

  public void updateWorkerHeartbeatMeta(
      String host,
      int rpcPort,
      int pushPort,
      int fetchPort,
      int replicatePort,
      Map<String, DiskInfo> disks,
      long time) {
    WorkerInfo worker =
        new WorkerInfo(host, rpcPort, pushPort, fetchPort, replicatePort, disks, null);
    AtomicLong availableSlots = new AtomicLong();
    LOG.debug("update worker {}:{} heart beat {}", host, rpcPort, disks);
    synchronized (workers) {
      Optional<WorkerInfo> workerInfo = workers.stream().filter(w -> w.equals(worker)).findFirst();
      workerInfo.ifPresent(
          info -> {
            info.updateDiskInfos(disks, estimatedPartitionSize);
            availableSlots.set(info.totalAvailableSlots());
            info.lastHeartbeat_$eq(time);
          });
    }
    if (!blacklist.contains(worker) && disks.isEmpty()) {
      LOG.debug("Worker: {} num total slots is 0, add to blacklist", worker);
      blacklist.add(worker);
    } else if (availableSlots.get() > 0) {
      // only unblack if numSlots larger than 0
      blacklist.remove(worker);
    }
  }

  public void updateRegisterWorkerMeta(
      String host,
      int rpcPort,
      int pushPort,
      int fetchPort,
      int replicatePort,
      Map<String, DiskInfo> disks) {
    WorkerInfo workerInfo =
        new WorkerInfo(host, rpcPort, pushPort, fetchPort, replicatePort, disks, null);
    workerInfo.lastHeartbeat_$eq(System.currentTimeMillis());

    try {
      workerInfo.setupEndpoint(rpcEnv.setupEndpointRef(RpcAddress.apply(host, rpcPort), WORKER_EP));
    } catch (Exception e) {
      LOG.error("Worker register failed", e);
      return;
    }

    workerInfo.updateDiskMaxSlots(estimatedPartitionSize);
    synchronized (workers) {
      workers.add(workerInfo);
    }
  }

  /**
   * Used for ratis state machine to take snapshot
   *
   * @param file
   * @throws IOException
   */
  public void writeMetaInfoToFile(File file) throws IOException, RuntimeException {
    ObjectOutputStream out =
        new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(file)));

    out.writeLong(estimatedPartitionSize);
    // write registeredShuffle
    writeSetMetaToFile(registeredShuffle, out);

    // write hostnameSet
    writeSetMetaToFile(hostnameSet, out);

    // write blacklist
    writeSetMetaToFile(blacklist, out);

    // write workerLost events
    writeSetMetaToFile(workerLostEvents, out);

    // write application heartbeat time
    out.writeInt(appHeartbeatTime.size());
    appHeartbeatTime.forEach(
        (app, time) -> {
          try {
            out.writeObject(app);
            out.writeLong(time);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });

    // write workerInfo
    out.writeInt(workers.size());
    workers.forEach(
        workerInfo -> {
          try {
            out.writeObject(workerInfo);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });

    out.flush();
  }

  private <T> void writeSetMetaToFile(Set<T> metas, ObjectOutputStream out) throws IOException {
    out.writeInt(metas.size());
    metas.forEach(
        meta -> {
          try {
            out.writeObject(meta);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }

  /**
   * Used for ratis state machine to load snapshot
   *
   * @param file
   * @throws IOException
   */
  public void restoreMetaFromFile(File file) throws IOException {
    try (ObjectInputStream in =
        new ObjectInputStream(new BufferedInputStream(new FileInputStream(file)))) {
      estimatedPartitionSize = in.readLong();
      // read registeredShuffle
      readSetMetaFromFile(registeredShuffle, in.readInt(), in);

      // read hostnameSet
      readSetMetaFromFile(hostnameSet, in.readInt(), in);

      // read blacklist
      readSetMetaFromFile(blacklist, in.readInt(), in);

      // read workerLost events
      readSetMetaFromFile(workerLostEvents, in.readInt(), in);

      // read application heartbeat time
      int size = in.readInt();
      for (int i = 0; i < size; ++i) {
        appHeartbeatTime.put((String) in.readObject(), in.readLong());
      }
      registeredShuffle.forEach(
          shuffleKey -> {
            String appId = shuffleKey.split("-")[0];
            if (!appHeartbeatTime.containsKey(appId)) {
              appHeartbeatTime.put(appId, System.currentTimeMillis());
            }
          });

      // read workerInfo
      int workersSize = in.readInt();
      for (int i = 0; i < workersSize; ++i) {
        workers.add((WorkerInfo) in.readObject());
      }
    } catch (ClassNotFoundException e) {
      throw new IOException(e);
    }
    LOG.info("Successfully restore meta info from snapshot " + file.getAbsolutePath());
    LOG.info(
        "Worker size: {}, Registered shuffle size: {}, Worker blacklist size: {}.",
        workers.size(),
        registeredShuffle.size(),
        blacklist.size());
    workers.forEach(workerInfo -> LOG.info(workerInfo.toString()));
    registeredShuffle.forEach(shuffle -> LOG.info("RegisteredShuffle " + shuffle));
  }

  private <T> void readSetMetaFromFile(Set<T> metas, int size, ObjectInputStream in)
      throws ClassNotFoundException, IOException {
    for (int i = 0; i < size; ++i) {
      metas.add((T) in.readObject());
    }
  }

  public void updateBlacklistByReportWorkerFailure(List<WorkerInfo> failedWorkers) {
    synchronized (this.workers) {
      failedWorkers.retainAll(this.workers);
      this.blacklist.addAll(failedWorkers);
    }
  }

  public void updatePartitionSize() {
    long oldEstimatedPartitionSize = estimatedPartitionSize;
    long tmpTotalWritten = partitionTotalWritten.sumThenReset();
    long tmpFileCount = partitionTotalFileCount.sumThenReset();
    LOG.debug("update partition size total written {} file count{}", tmpTotalWritten, tmpFileCount);
    if (tmpFileCount != 0) {
      estimatedPartitionSize = tmpTotalWritten / tmpFileCount;
    } else {
      estimatedPartitionSize = defaultPartitionSize;
    }
    LOG.warn(
        "Rss cluster estimated partition size changed from {} to {}",
        oldEstimatedPartitionSize,
        estimatedPartitionSize);
    workers.stream()
        .filter(worker -> !blacklist.contains(worker))
        .forEach(workerInfo -> workerInfo.updateDiskMaxSlots(estimatedPartitionSize));
  }
}
