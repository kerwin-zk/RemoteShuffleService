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

package org.apache.celeborn.plugin.flink.buffer;

import java.io.IOException;
import java.nio.ByteBuffer;

import javax.annotation.Nullable;

import org.apache.flink.core.memory.MemorySegment;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.buffer.BufferRecycler;

/**
 * Data of different subpartitions can be appended to a {@link DataBuffer} and after the {@link
 * DataBuffer} is full or finished, the appended data can be copied from it in subpartition index
 * order.
 *
 * <p>The lifecycle of a {@link DataBuffer} can be: new, write, [read, reset, write], finish, read,
 * release. There can be multiple [read, reset, write] operations before finish.
 */
public interface DataBuffer {

  /**
   * Appends data of the specified subpartition to this {@link DataBuffer} and returns true if this
   * {@link DataBuffer} is full.
   */
  boolean append(ByteBuffer source, int targetSubpartition, Buffer.DataType dataType)
      throws IOException;

  /**
   * Copies data in this {@link DataBuffer} to the target {@link MemorySegment} in subpartition
   * index order and returns {@link BufferWithSubpartition} which contains the copied data and the
   * corresponding subpartition index.
   */
  BufferWithSubpartition getNextBuffer(
      @Nullable MemorySegment transitBuffer, BufferRecycler recycler, int offset);

  /** Returns the total number of records written to this {@link DataBuffer}. */
  long numTotalRecords();

  /** Returns the total number of bytes written to this {@link DataBuffer}. */
  long numTotalBytes();

  /** Returns true if not all data appended to this {@link DataBuffer} is consumed. */
  boolean hasRemaining();

  /** Finishes this {@link DataBuffer} which means no record can be appended anymore. */
  void finish();

  /** Whether this {@link DataBuffer} is finished or not. */
  boolean isFinished();

  /** Releases this {@link DataBuffer} which releases all resources. */
  void release();

  /** Whether this {@link DataBuffer} is released or not. */
  boolean isReleased();
}
