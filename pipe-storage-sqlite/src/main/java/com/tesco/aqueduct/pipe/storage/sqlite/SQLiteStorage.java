package com.tesco.aqueduct.pipe.storage.sqlite;

import com.tesco.aqueduct.pipe.api.JsonHelper;
import com.tesco.aqueduct.pipe.api.Message;
import com.tesco.aqueduct.pipe.api.MessageResults;
import com.tesco.aqueduct.pipe.api.MessageStorage;
import com.tesco.aqueduct.pipe.logger.PipeLogger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.*;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

public class SQLiteStorage implements MessageStorage {

    private final DataSource dataSource;
    private final int limit;
    private final int retryAfterSeconds;
    private final long maxBatchSize;

    private static final PipeLogger LOG = new PipeLogger(LoggerFactory.getLogger(SQLiteStorage.class));

    public SQLiteStorage(final DataSource dataSource, final int limit, final int retryAfterSeconds, final long maxBatchSize) {
        this.dataSource = dataSource;
        this.limit = limit;
        this.retryAfterSeconds = retryAfterSeconds;
        this.maxBatchSize = maxBatchSize + (((long)Message.MAX_OVERHEAD_SIZE) * limit);

        createEventTableIfExists();
    }

    private void createEventTableIfExists() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(EventQueries.CREATE_EVENT_TABLE)) {

            statement.execute();
        } catch (SQLException e) {

            throw new RuntimeException(e);
        }
    }

    @Override
    public MessageResults read(final List<String> types, final long offset) {
        final List<Message> retrievedMessages = new ArrayList<>();
        final int typesCount = types == null ? 0 : types.size();

        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(EventQueries.getReadEvent(typesCount, maxBatchSize))
        ) {
            int parameterIndex = 1;
            statement.setLong(parameterIndex++, offset);

            for (int i = 0; i < typesCount; i++, parameterIndex++) {
                statement.setString(parameterIndex, types.get(i));
            }

            statement.setLong(parameterIndex, limit);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    retrievedMessages.add(mapRetrievedMessageFromResultSet(resultSet));
                }
            }

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return new MessageResults(retrievedMessages, calculateRetryAfter(retrievedMessages.size()));
    }

    public int calculateRetryAfter(final int messageCount) {
        return messageCount > 0 ? 0 : retryAfterSeconds;
    }

    private Message mapRetrievedMessageFromResultSet(final ResultSet resultSet) throws SQLException {
        Message retrievedMessage;
        final ZonedDateTime time = ZonedDateTime.of(
            resultSet.getTimestamp("created_utc").toLocalDateTime(),
            ZoneId.of("UTC")
        );

        retrievedMessage = new Message(
            resultSet.getString("type"),
            resultSet.getString("msg_key"),
            resultSet.getString("content_type"),
            resultSet.getLong("msg_offset"),
            time,
            resultSet.getString("data"),
            resultSet.getLong("event_size")
        );

        return retrievedMessage;
    }

    @Override
    public long getLatestOffsetMatching(final List<String> types) {
        final int typesCount = types == null ? 0 : types.size();

        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(EventQueries.getLastOffsetQuery(typesCount))
        ) {
            for (int i = 0; i < typesCount; i++) {
                statement.setString(i + 1, types.get(i));
            }

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.getLong("last_offset");
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void write(final Iterable<Message> messages) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(EventQueries.INSERT_EVENT)) {
            connection.setAutoCommit(false);

            for (final Message message : messages) {
                setStatementParametersForInsertMessageQuery(statement, message);
                statement.addBatch();
            }

            statement.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void write(final Message message) {
        try (Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(EventQueries.INSERT_EVENT)) {
            setStatementParametersForInsertMessageQuery(statement, message);

            statement.execute();
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public void compactUpTo(final ZonedDateTime zonedDateTime) {
        // We may want a interface - Compactable - for this method signature
        try (Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(EventQueries.COMPACT)) {
            Timestamp threshold = Timestamp.valueOf(zonedDateTime.withZoneSameInstant(ZoneId.of("UTC")).toLocalDateTime());
            statement.setTimestamp(1, threshold);
            statement.setTimestamp(2, threshold);
            final int rowsAffected = statement.executeUpdate();
            LOG.info("compaction", "compacted " + rowsAffected + " rows");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void setStatementParametersForInsertMessageQuery(final PreparedStatement statement, final Message message)
            throws SQLException {
        try {
            statement.setLong(1, message.getOffset());
            statement.setString(2, message.getKey());
            statement.setString(3, message.getContentType());
            statement.setString(4, message.getType());
            statement.setTimestamp(5, Timestamp.valueOf(message.getCreated().withZoneSameInstant(ZoneId.of("UTC")).toLocalDateTime()));
            statement.setString(6, message.getData());
            statement.setInt(7, JsonHelper.toJson(message).length());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}