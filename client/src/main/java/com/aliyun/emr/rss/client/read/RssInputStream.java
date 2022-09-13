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

package com.aliyun.emr.rss.client.read;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aliyun.emr.rss.client.compress.Decompressor;
import com.aliyun.emr.rss.common.RssConf;
import com.aliyun.emr.rss.common.network.client.TransportClientFactory;
import com.aliyun.emr.rss.common.protocol.PartitionLocation;
import com.aliyun.emr.rss.common.protocol.StorageInfo;
import com.aliyun.emr.rss.common.unsafe.Platform;

public abstract class RssInputStream extends InputStream {
  private static final Logger logger = LoggerFactory.getLogger(RssInputStream.class);

  public static RssInputStream create(
      RssConf conf,
      TransportClientFactory clientFactory,
      String shuffleKey,
      PartitionLocation[] locations,
      int[] attempts,
      int attemptNumber,
      int startMapIndex,
      int endMapIndex)
      throws IOException {
    if (locations == null || locations.length == 0) {
      return emptyInputStream;
    } else {
      return new RssInputStreamImpl(
          conf,
          clientFactory,
          shuffleKey,
          locations,
          attempts,
          attemptNumber,
          startMapIndex,
          endMapIndex);
    }
  }

  public static RssInputStream empty() {
    return emptyInputStream;
  }

  public abstract void setCallback(MetricsCallback callback);

  private static final RssInputStream emptyInputStream =
      new RssInputStream() {
        @Override
        public int read() throws IOException {
          return -1;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
          return -1;
        }

        @Override
        public void setCallback(MetricsCallback callback) {}
      };

  private static final class RssInputStreamImpl extends RssInputStream {
    private final RssConf conf;
    private final TransportClientFactory clientFactory;
    private final String shuffleKey;
    private final PartitionLocation[] locations;
    private final int[] attempts;
    private final int attemptNumber;
    private final int startMapIndex;
    private final int endMapIndex;

    private final Map<Integer, Set<Integer>> batchesRead = new HashMap<>();

    private byte[] compressedBuf;
    private byte[] decompressedBuf;
    private final Decompressor decompressor;

    private ByteBuf currentChunk;
    private PartitionReader currentReader;
    private int fileIndex;
    private int position;
    private int limit;

    private MetricsCallback callback;

    // mapId, attemptId, batchId, size
    private final int BATCH_HEADER_SIZE = 4 * 4;
    private final byte[] sizeBuf = new byte[BATCH_HEADER_SIZE];

    RssInputStreamImpl(
        RssConf conf,
        TransportClientFactory clientFactory,
        String shuffleKey,
        PartitionLocation[] locations,
        int[] attempts,
        int attemptNumber,
        int startMapIndex,
        int endMapIndex)
        throws IOException {
      this.conf = conf;
      this.clientFactory = clientFactory;
      this.shuffleKey = shuffleKey;

      List<PartitionLocation> shuffledLocations =
          new ArrayList() {
            {
              addAll(Arrays.asList(locations));
            }
          };
      Collections.shuffle(shuffledLocations);
      this.locations = shuffledLocations.toArray(new PartitionLocation[locations.length]);

      this.attempts = attempts;
      this.attemptNumber = attemptNumber;
      this.startMapIndex = startMapIndex;
      this.endMapIndex = endMapIndex;

      int headerLen = Decompressor.getCompressionHeaderLength(conf);
      int blockSize = RssConf.pushDataBufferSize(conf) + headerLen;
      compressedBuf = new byte[blockSize];
      decompressedBuf = new byte[blockSize];

      decompressor = Decompressor.getDecompressor(conf);

      moveToNextReader();
    }

    private void moveToNextReader() throws IOException {
      if (currentReader != null) {
        currentReader.close();
      }

      int locationCount = locations.length;
      PartitionLocation currentLocation = locations[fileIndex];
      currentReader = createReader(currentLocation);
      logger.debug(
          "Moved to next partition {},startMapIndex {} endMapIndex {} , {}/{} read ",
          currentLocation,
          startMapIndex,
          endMapIndex,
          fileIndex,
          locationCount);
      while (!currentReader.hasNext() && fileIndex < locationCount - 1) {
        fileIndex++;
        currentLocation = locations[fileIndex];
        currentReader.close();
        currentReader = createReader(currentLocation);
        logger.debug(
            "Moved to next partition {},startMapIndex {} endMapIndex {} , {}/{} read ",
            currentLocation,
            startMapIndex,
            endMapIndex,
            fileIndex,
            locationCount);
      }
      if (currentReader.hasNext()) {
        currentChunk = currentReader.next();
        fileIndex++;
      } else {
        currentReader.close();
        currentReader = null;
      }
    }

    private PartitionReader createReader(PartitionLocation location) throws IOException {
      if (location.getPeer() == null) {
        logger.debug("Partition {} has only one partition replica.", location);
      }
      if (location.getPeer() != null && attemptNumber % 2 == 1) {
        location = location.getPeer();
        logger.debug("Read peer {} for attempt {}.", location, attemptNumber);
      }

      StorageInfo storageInfo = location.getStorageInfo();
      if (storageInfo.getType() == StorageInfo.Type.HDD
          || storageInfo.getType() == StorageInfo.Type.SSD) {
        return new WorkerPartitionReader(
            conf, shuffleKey, location, clientFactory, startMapIndex, endMapIndex);
      }
      if (storageInfo.getType() == StorageInfo.Type.HDFS) {
        return new DfsPartitionReader(
            conf, shuffleKey, location, clientFactory, startMapIndex, endMapIndex);
      }

      throw new IOException(
          "Unknown storage info " + storageInfo + " to read location " + location);
    }

    public void setCallback(MetricsCallback callback) {
      // callback must set before read()
      this.callback = callback;
    }

    @Override
    public int read() throws IOException {
      if (position < limit) {
        int b = decompressedBuf[position];
        position++;
        return b & 0xFF;
      }

      if (!fillBuffer()) {
        return -1;
      }

      if (position >= limit) {
        return read();
      } else {
        int b = decompressedBuf[position];
        position++;
        return b & 0xFF;
      }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (b == null) {
        throw new NullPointerException();
      } else if (off < 0 || len < 0 || len > b.length - off) {
        throw new IndexOutOfBoundsException();
      } else if (len == 0) {
        return 0;
      }

      int readBytes = 0;
      while (readBytes < len) {
        while (position >= limit) {
          if (!fillBuffer()) {
            return readBytes > 0 ? readBytes : -1;
          }
        }

        int bytesToRead = Math.min(limit - position, len - readBytes);
        System.arraycopy(decompressedBuf, position, b, off + readBytes, bytesToRead);
        position += bytesToRead;
        readBytes += bytesToRead;
      }

      return readBytes;
    }

    @Override
    public void close() {
      if (currentChunk != null) {
        logger.debug("Release chunk {}!", currentChunk);
        currentChunk.release();
        currentChunk = null;
      }
      if (currentReader != null) {
        logger.debug("Closing reader");
        currentReader.close();
        currentReader = null;
      }
    }

    private boolean moveToNextChunk() throws IOException {
      if (currentChunk != null) {
        currentChunk.release();
      }
      currentChunk = null;
      if (currentReader.hasNext()) {
        currentChunk = currentReader.next();
        return true;
      } else if (fileIndex < locations.length) {
        moveToNextReader();
        return currentReader != null;
      }
      if (currentReader != null) {
        currentReader.close();
        currentReader = null;
      }
      return false;
    }

    private boolean fillBuffer() throws IOException {
      if (currentChunk == null) {
        return false;
      }

      long startTime = System.currentTimeMillis();

      boolean hasData = false;
      while (currentChunk.isReadable() || moveToNextChunk()) {
        currentChunk.readBytes(sizeBuf);
        int mapId = Platform.getInt(sizeBuf, Platform.BYTE_ARRAY_OFFSET);
        int attemptId = Platform.getInt(sizeBuf, Platform.BYTE_ARRAY_OFFSET + 4);
        int batchId = Platform.getInt(sizeBuf, Platform.BYTE_ARRAY_OFFSET + 8);
        int size = Platform.getInt(sizeBuf, Platform.BYTE_ARRAY_OFFSET + 12);
        if (size > compressedBuf.length) {
          compressedBuf = new byte[size];
        }

        currentChunk.readBytes(compressedBuf, 0, size);

        // de-duplicate
        if (attemptId == attempts[mapId]) {
          if (!batchesRead.containsKey(mapId)) {
            Set<Integer> batchSet = new HashSet<>();
            batchesRead.put(mapId, batchSet);
          }
          Set<Integer> batchSet = batchesRead.get(mapId);
          if (!batchSet.contains(batchId)) {
            batchSet.add(batchId);
            if (callback != null) {
              callback.incBytesRead(BATCH_HEADER_SIZE + size);
            }
            // decompress data
            int originalLength = decompressor.getOriginalLen(compressedBuf);
            if (decompressedBuf.length < originalLength) {
              decompressedBuf = new byte[originalLength];
            }
            limit = decompressor.decompress(compressedBuf, decompressedBuf, 0);
            position = 0;
            hasData = true;
            break;
          } else {
            logger.debug(
                "Skip duplicated batch: mapId {}, attemptId {}," + " batchId {}.",
                mapId,
                attemptId,
                batchId);
          }
        }
      }

      if (callback != null) {
        callback.incReadTime(System.currentTimeMillis() - startTime);
      }
      return hasData;
    }
  }
}
