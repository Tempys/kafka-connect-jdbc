/**
 * Copyright 2015 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package io.confluent.connect.jdbc.source;

import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.utils.SystemTime;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.confluent.connect.jdbc.dialect.DatabaseDialect;
import io.confluent.connect.jdbc.dialect.DatabaseDialects;
import io.confluent.connect.jdbc.util.CachedConnectionProvider;
import io.confluent.connect.jdbc.util.ColumnDefinition;
import io.confluent.connect.jdbc.util.ColumnId;
import io.confluent.connect.jdbc.util.Version;

/**
 * JdbcSourceTask is a Kafka Connect SourceTask implementation that reads from JDBC databases and
 * generates Kafka Connect records.
 */
public class JdbcSourceTask extends SourceTask {

  private static final Logger log = LoggerFactory.getLogger(JdbcSourceTask.class);

  private Time time;
  private JdbcSourceTaskConfig config;
  private DatabaseDialect dialect;
  private CachedConnectionProvider cachedConnectionProvider;
  private PriorityQueue<TableQuerier> tableQueue = new PriorityQueue<TableQuerier>();
  private final AtomicBoolean running = new AtomicBoolean(false);

  public JdbcSourceTask() {
    this.time = new SystemTime();
  }

  public JdbcSourceTask(Time time) {
    this.time = time;
  }

  @Override
  public String version() {
    return Version.getVersion();
  }

  @Override
  public void start(Map<String, String> properties) {
    try {
      config = new JdbcSourceTaskConfig(properties);
    } catch (ConfigException e) {
      throw new ConnectException("Couldn't start JdbcSourceTask due to configuration error", e);
    }

    final String url = config.getString(JdbcSourceConnectorConfig.CONNECTION_URL_CONFIG);
    final int maxConnAttempts = config.getInt(JdbcSourceConnectorConfig.CONNECTION_ATTEMPTS_CONFIG);
    final long retryBackoff = config.getLong(JdbcSourceConnectorConfig.CONNECTION_BACKOFF_CONFIG);

    final String dialectName = config.getString(JdbcSourceConnectorConfig.DIALECT_NAME_CONFIG);
    if (dialectName != null && !dialectName.trim().isEmpty()) {
      dialect = DatabaseDialects.create(dialectName, config);
    } else {
      dialect = DatabaseDialects.findBestFor(url, config);
    }

    cachedConnectionProvider = new CachedConnectionProvider(dialect, maxConnAttempts, retryBackoff);

    List<String> tables = config.getList(JdbcSourceTaskConfig.TABLES_CONFIG);
    String query = config.getString(JdbcSourceTaskConfig.QUERY_CONFIG);
    if ((tables.isEmpty() && query.isEmpty()) || (!tables.isEmpty() && !query.isEmpty())) {
      throw new ConnectException("Invalid configuration: each JdbcSourceTask must have at "
                                        + "least one table assigned to it or one query specified");
    }
    TableQuerier.QueryMode queryMode = !query.isEmpty() ? TableQuerier.QueryMode.QUERY :
                                       TableQuerier.QueryMode.TABLE;
    List<String> tablesOrQuery = queryMode == TableQuerier.QueryMode.QUERY
                                 ? Collections.singletonList(query) : tables;

    String mode = config.getString(JdbcSourceTaskConfig.MODE_CONFIG);
    Map<Map<String, String>, Map<String, Object>> offsets = null;
    if (mode.equals(JdbcSourceTaskConfig.MODE_INCREMENTING)
        || mode.equals(JdbcSourceTaskConfig.MODE_TIMESTAMP)
        || mode.equals(JdbcSourceTaskConfig.MODE_TIMESTAMP_INCREMENTING)) {
      List<Map<String, String>> partitions = new ArrayList<>(tables.size());
      switch (queryMode) {
        case TABLE:
          for (String table : tables) {
            Map<String, String> partition =
                Collections.singletonMap(JdbcSourceConnectorConstants.TABLE_NAME_KEY, table);
            partitions.add(partition);
          }
          break;
        case QUERY:
          partitions.add(Collections.singletonMap(JdbcSourceConnectorConstants.QUERY_NAME_KEY,
                                                  JdbcSourceConnectorConstants.QUERY_NAME_VALUE));
          break;
        default:
          throw new ConnectException("Unknown query mode: " + queryMode);
      }
      offsets = context.offsetStorageReader().offsets(partitions);
    }

    String incrementingColumn
        = config.getString(JdbcSourceTaskConfig.INCREMENTING_COLUMN_NAME_CONFIG);
    List<String> timestampColumns
        = config.getList(JdbcSourceTaskConfig.TIMESTAMP_COLUMN_NAME_CONFIG);
    Long timestampDelayInterval
        = config.getLong(JdbcSourceTaskConfig.TIMESTAMP_DELAY_INTERVAL_MS_CONFIG);
    boolean validateNonNulls
        = config.getBoolean(JdbcSourceTaskConfig.VALIDATE_NON_NULL_CONFIG);

    for (String tableOrQuery : tablesOrQuery) {
      final Map<String, String> partition;
      switch (queryMode) {
        case TABLE:
          if (validateNonNulls) {
            validateNonNullable(
                mode,
                tableOrQuery,
                incrementingColumn,
                timestampColumns
            );
          }
          partition = Collections.singletonMap(
              JdbcSourceConnectorConstants.TABLE_NAME_KEY,
              tableOrQuery
          );
          break;
        case QUERY:
          partition = Collections.singletonMap(
              JdbcSourceConnectorConstants.QUERY_NAME_KEY,
              JdbcSourceConnectorConstants.QUERY_NAME_VALUE
          );
          break;
        default:
          throw new ConnectException("Unexpected query mode: " + queryMode);
      }
      Map<String, Object> offset = offsets == null ? null : offsets.get(partition);

      String topicPrefix = config.getString(JdbcSourceTaskConfig.TOPIC_PREFIX_CONFIG);

      if (mode.equals(JdbcSourceTaskConfig.MODE_BULK)) {
        tableQueue.add(
            new BulkTableQuerier(dialect, queryMode, tableOrQuery, topicPrefix)
        );
      } else if (mode.equals(JdbcSourceTaskConfig.MODE_INCREMENTING)) {
        tableQueue.add(
            new TimestampIncrementingTableQuerier(
                dialect,
                queryMode,
                tableOrQuery,
                topicPrefix,
                null,
                incrementingColumn,
                offset,
                timestampDelayInterval
            )
        );
      } else if (mode.equals(JdbcSourceTaskConfig.MODE_TIMESTAMP)) {
        tableQueue.add(
            new TimestampIncrementingTableQuerier(
                dialect,
                queryMode,
                tableOrQuery,
                topicPrefix,
                timestampColumns,
                null,
                offset,
                timestampDelayInterval
            )
        );
      } else if (mode.endsWith(JdbcSourceTaskConfig.MODE_TIMESTAMP_INCREMENTING)) {
        tableQueue.add(
            new TimestampIncrementingTableQuerier(
                dialect,
                queryMode,
                tableOrQuery,
                topicPrefix,
                timestampColumns,
                incrementingColumn,
                offset,
                timestampDelayInterval
            )
        );
      }
    }

    running.set(true);
  }

  @Override
  public void stop() throws ConnectException {
    try {
      running.set(false);
      if (cachedConnectionProvider != null) {
        cachedConnectionProvider.close();
      }
    } finally {
      cachedConnectionProvider = null;
      try {
        if (dialect != null) {
          dialect.close();
        }
      } catch (Throwable t) {
        log.warn("Error while closing the {} dialect: ", dialect, t);
      } finally {
        dialect = null;
      }
    }
  }

  @Override
  public List<SourceRecord> poll() throws InterruptedException {
    log.trace("{} Polling for new data");

    while (running.get()) {
      final TableQuerier querier = tableQueue.peek();

      if (!querier.querying()) {
        // If not in the middle of an update, wait for next update time
        final long nextUpdate = querier.getLastUpdate()
                                + config.getInt(JdbcSourceTaskConfig.POLL_INTERVAL_MS_CONFIG);
        final long untilNext = nextUpdate - time.milliseconds();
        if (untilNext > 0) {
          log.trace("Waiting {} ms to poll {} next", untilNext, querier.toString());
          time.sleep(untilNext);
          continue; // Re-check stop flag before continuing
        }
      }

      final List<SourceRecord> results = new ArrayList<>();
      try {
        log.debug("Checking for next block of results from {}", querier.toString());
        querier.maybeStartQuery(cachedConnectionProvider.getConnection());

        int batchMaxRows = config.getInt(JdbcSourceTaskConfig.BATCH_MAX_ROWS_CONFIG);
        boolean hadNext = true;
        while (results.size() < batchMaxRows && (hadNext = querier.next())) {
          results.add(querier.extractRecord());
        }

        if (!hadNext) {
          // If we finished processing the results from the current query, we can reset and send
          // the querier to the tail of the queue
          resetAndRequeueHead(querier);
        }

        if (results.isEmpty()) {
          log.trace("No updates for {}", querier.toString());
          continue;
        }

        log.debug("Returning {} records for {}", results.size(), querier.toString());
        return results;
      } catch (SQLException sqle) {
        log.error("Failed to run query for table {}: {}", querier.toString(), sqle);
        resetAndRequeueHead(querier);
        return null;
      } catch (Throwable t) {
        resetAndRequeueHead(querier);
        throw t;
      }
    }

    // Only in case of shutdown
    final TableQuerier querier = tableQueue.peek();
    if (querier != null) {
      resetAndRequeueHead(querier);
    }
    return null;
  }

  private void resetAndRequeueHead(TableQuerier expectedHead) {
    log.debug("Resetting querier {}", expectedHead.toString());
    TableQuerier removedQuerier = tableQueue.poll();
    assert removedQuerier == expectedHead;
    expectedHead.reset(time.milliseconds());
    tableQueue.add(expectedHead);
  }

  private void validateNonNullable(
      String incrementalMode,
      String table,
      String incrementingColumn,
      List<String> timestampColumns
  ) {
    try {
      Set<String> lowercaseTsColumns = new HashSet<>();
      for (String timestampColumn: timestampColumns) {
        lowercaseTsColumns.add(timestampColumn.toLowerCase(Locale.getDefault()));
      }

      boolean incrementingOptional = false;
      boolean atLeastOneTimestampNotOptional = false;
      final Connection conn = cachedConnectionProvider.getConnection();
      Map<ColumnId, ColumnDefinition> defnsById = dialect.describeColumns(conn, table, null);
      for (ColumnDefinition defn : defnsById.values()) {
        String columnName = defn.id().name();
        if (columnName.equalsIgnoreCase(incrementingColumn)) {
          incrementingOptional = defn.isOptional();
        } else if (lowercaseTsColumns.contains(columnName.toLowerCase(Locale.getDefault()))) {
          if (!defn.isOptional()) {
            atLeastOneTimestampNotOptional = true;
          }
        }
      }

      // Validate that requested columns for offsets are NOT NULL. Currently this is only performed
      // for table-based copying because custom query mode doesn't allow this to be looked up
      // without a query or parsing the query since we don't have a table name.
      if ((incrementalMode.equals(JdbcSourceConnectorConfig.MODE_INCREMENTING)
           || incrementalMode.equals(JdbcSourceConnectorConfig.MODE_TIMESTAMP_INCREMENTING))
          && incrementingOptional) {
        throw new ConnectException("Cannot make incremental queries using incrementing column "
                                   + incrementingColumn + " on " + table + " because this column "
                                   + "is nullable.");
      }
      if ((incrementalMode.equals(JdbcSourceConnectorConfig.MODE_TIMESTAMP)
           || incrementalMode.equals(JdbcSourceConnectorConfig.MODE_TIMESTAMP_INCREMENTING))
          && !atLeastOneTimestampNotOptional) {
        throw new ConnectException("Cannot make incremental queries using timestamp columns "
                                   + timestampColumns + " on " + table + " because all of these "
                                   + "columns "
                                   + "nullable.");
      }
    } catch (SQLException e) {
      throw new ConnectException("Failed trying to validate that columns used for offsets are NOT"
                                 + " NULL", e);
    }
  }
}
