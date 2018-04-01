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

package org.apache.carbondata.store;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.carbondata.common.annotations.InterfaceAudience;
import org.apache.carbondata.core.datastore.row.CarbonRow;
import org.apache.carbondata.core.metadata.schema.table.CarbonTable;
import org.apache.carbondata.core.scan.expression.Expression;
import org.apache.carbondata.hadoop.CarbonProjection;
import org.apache.carbondata.hadoop.api.CarbonTableInputFormat;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobID;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.task.JobContextImpl;
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl;

/**
 * A CarbonStore implementation that works locally, without other compute framework dependency.
 * It can be used to read data in local disk.
 *
 * Note that this class is experimental, it is not intended to be used in production.
 */
@InterfaceAudience.Internal
class LocalCarbonStore extends MetaCachedCarbonStore {

  @Override
  public CarbonRow[] scan(String path, String[] projectColumns) throws IOException {
    return scan(path, projectColumns, null);
  }

  @Override
  public CarbonRow[] scan(String path, String[] projectColumns, Expression filter)
      throws IOException {
    CarbonTable table = getTable(path);
    if (table.isStreamingTable() || table.isHivePartitionTable()) {
      throw new UnsupportedOperationException("streaming and partition table is not supported");
    }
    // TODO: use InputFormat to prune data and read data

    final CarbonTableInputFormat format = new CarbonTableInputFormat();
    final Job job = new Job(new Configuration());
    format.setTableInfo(job.getConfiguration(), table.getTableInfo());
    format.setTablePath(job.getConfiguration(), table.getTablePath());
    format.setTableName(job.getConfiguration(), table.getTableName());
    format.setDatabaseName(job.getConfiguration(), table.getDatabaseName());
    format.setCarbonReadSupport(job.getConfiguration(), CarbonRowReadSupport.class);
    if (filter != null) {
      format.setFilterPredicates(job.getConfiguration(), filter);
    }
    if (projectColumns != null) {
      format.setColumnProjection(job.getConfiguration(), new CarbonProjection(projectColumns));
    }

    final List<InputSplit> splits =
        format.getSplits(new JobContextImpl(job.getConfiguration(), new JobID()));

    List<RecordReader<Void, Object>> readers = new ArrayList<>(splits.size());

    try {
      for (InputSplit split : splits) {
        TaskAttemptContextImpl attempt =
            new TaskAttemptContextImpl(job.getConfiguration(), new TaskAttemptID());
        RecordReader reader = format.createRecordReader(split, attempt);
        reader.initialize(split, attempt);
        readers.add(reader);
      }
    } catch (InterruptedException e) {
      throw new IOException(e);
    }

    List<CarbonRow> rows = new ArrayList<>();
    try {
      for (RecordReader<Void, Object> reader : readers) {
        while (reader.nextKeyValue()) {
          rows.add((CarbonRow)reader.getCurrentValue());
        }
      }
    } catch (InterruptedException e) {
      throw new IOException(e);
    }
    return rows.toArray(new CarbonRow[rows.size()]);
  }

  @Override
  public CarbonRow[] sql(String sqlString) throws IOException {
    throw new UnsupportedOperationException();
  }
}
