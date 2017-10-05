/**
 * Copyright 2015-2017 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin.storage.cassandra3;

import com.datastax.driver.core.Session;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import javax.annotation.Nullable;
import zipkin.internal.LazyCloseable;
import zipkin2.CheckResult;
import zipkin2.storage.QueryRequest;
import zipkin2.storage.SpanConsumer;
import zipkin2.storage.SpanStore;
import zipkin2.storage.StorageComponent;

import static zipkin.internal.Util.checkNotNull;


/**
 * CQL3 implementation of zipkin storage.
 *
 * <p>Queries are logged to the category "com.datastax.driver.core.QueryLogger" when debug or trace
 * is enabled via SLF4J. Trace level includes bound values.
 *
 * <p>Schema is installed by default from "/cassandra3-schema.cql"
 */
// This component is named Cassandra3Storage as it correlates to "cassandra3" storage types, and
// makes health-checks more obvious. Note: this is the only public type in the package.
public final class Cassandra3Storage extends StorageComponent {

  // @FunctionalInterface, except safe for lower language levels
  public interface SessionFactory {
    SessionFactory DEFAULT = new DefaultSessionFactory();

    Session create(Cassandra3Storage storage);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder extends StorageComponent.Builder {
    boolean strictTraceId = true;
    String keyspace = Schema.DEFAULT_KEYSPACE;
    String contactPoints = "localhost";
    String localDc;
    int maxConnections = 8;
    boolean ensureSchema = true;
    boolean useSsl = false;
    String username;
    String password;
    int maxTraceCols = 100000;
    int indexFetchMultiplier = 3;
    SessionFactory sessionFactory = SessionFactory.DEFAULT;

    /** {@inheritDoc} */
    @Override public Builder strictTraceId(boolean strictTraceId) {
      this.strictTraceId = strictTraceId;
      return this;
    }

    /** Override to control how sessions are created. */
    public Builder sessionFactory(SessionFactory sessionFactory) {
      this.sessionFactory = checkNotNull(sessionFactory, "sessionFactory");
      return this;
    }

    /** Keyspace to store span and index data. Defaults to "zipkin3" */
    public Builder keyspace(String keyspace) {
      this.keyspace = checkNotNull(keyspace, "keyspace");
      return this;
    }

    /** Comma separated list of host addresses part of Cassandra cluster. You can also specify a custom port with 'host:port'. Defaults to localhost on port 9042 **/
    public Builder contactPoints(String contactPoints) {
      this.contactPoints = checkNotNull(contactPoints, "contactPoints");
      return this;
    }

    /**
     * Name of the datacenter that will be considered "local" for latency load balancing. When
     * unset, load-balancing is round-robin.
     */
    public Builder localDc(@Nullable String localDc) {
      this.localDc = localDc;
      return this;
    }

    /** Max pooled connections per datacenter-local host. Defaults to 8 */
    public Builder maxConnections(int maxConnections) {
      this.maxConnections = maxConnections;
      return this;
    }

    /**
     * Ensures that schema exists, if enabled tries to execute script io.zipkin:zipkin-cassandra-core/cassandra-schema-cql3.txt.
     * Defaults to true.
     */
    public Builder ensureSchema(boolean ensureSchema) {
      this.ensureSchema = ensureSchema;
      return this;
    }

    /**
     * Use ssl for driver
     * Defaults to false.
     */
    public Builder useSsl(boolean useSsl) {
      this.useSsl = useSsl;
      return this;
    }

    /** Will throw an exception on startup if authentication fails. No default. */
    public Builder username(@Nullable String username) {
      this.username = username;
      return this;
    }

    /** Will throw an exception on startup if authentication fails. No default. */
    public Builder password(@Nullable String password) {
      this.password = password;
      return this;
    }

    /**
     * Spans have multiple values for the same id. For example, a client and server contribute to
     * the same span id. When searching for spans by id, the amount of results may be larger than
     * the ids. This defines a threshold which accommodates this situation, without looking for an
     * unbounded number of results.
     */
    public Builder maxTraceCols(int maxTraceCols) {
      this.maxTraceCols = maxTraceCols;
      return this;
    }

    /**
     * How many more index rows to fetch than the user-supplied query limit. Defaults to 3.
     *
     * <p>Backend requests will request {@link QueryRequest#limit} times this factor rows from
     * Cassandra indexes in attempts to return {@link QueryRequest#limit} traces.
     *
     * <p>Indexing in cassandra will usually have more rows than trace identifiers due to factors
     * including table design and collection implementation. As there's no way to DISTINCT out
     * duplicates server-side, this over-fetches client-side when {@code indexFetchMultiplier} > 1.
     */
    public Builder indexFetchMultiplier(int indexFetchMultiplier) {
      this.indexFetchMultiplier = indexFetchMultiplier;
      return this;
    }

    @Override public Cassandra3Storage build() {
      return new Cassandra3Storage(this);
    }

    Builder() {
    }
  }

  final int maxTraceCols;
  final String contactPoints;
  final int maxConnections;
  final String localDc;
  final String username;
  final String password;
  final boolean ensureSchema;
  final boolean useSsl;
  final String keyspace;
  final int indexFetchMultiplier;
  final boolean strictTraceId;
  final LazyCloseable<Session> session;

  Cassandra3Storage(Builder builder) {
    this.contactPoints = builder.contactPoints;
    this.maxConnections = builder.maxConnections;
    this.localDc = builder.localDc;
    this.username = builder.username;
    this.password = builder.password;
    this.ensureSchema = builder.ensureSchema;
    this.useSsl = builder.useSsl;
    this.keyspace = builder.keyspace;
    this.maxTraceCols = builder.maxTraceCols;
    this.indexFetchMultiplier = builder.indexFetchMultiplier;
    this.strictTraceId = builder.strictTraceId;
    final SessionFactory sessionFactory = builder.sessionFactory;
    this.session = new LazyCloseable<Session>() {
      @Override protected Session compute() {
        return sessionFactory.create(Cassandra3Storage.this);
      }
    };
  }

  /** Lazy initializes or returns the session in use by this storage component. */
  public Session session() {
    return session.get();
  }

  @Override public SpanStore spanStore() {
    return new CassandraSpanStore(session.get(), maxTraceCols, indexFetchMultiplier, strictTraceId);
  }

  @Override public SpanConsumer spanConsumer() {
    return new CassandraSpanConsumer(session.get(), strictTraceId);
  }

  @Override public CheckResult check() {
    try {
      session.get().execute(QueryBuilder.select("trace_id").from("traces").limit(1));
    } catch (RuntimeException e) {
      return CheckResult.failed(e);
    }
    return CheckResult.OK;
  }

  @Override public void close() throws IOException {
    session.close();
  }

  /** Truncates all the column families, or throws on any failure. */
  @VisibleForTesting void clear() {
    for (String cf : ImmutableList.of(
      Schema.TABLE_TRACES,
      Schema.TABLE_TRACE_BY_SERVICE_SPAN,
      Schema.TABLE_SERVICE_SPANS,
      Schema.TABLE_DEPENDENCIES
    )) {
      session.get().execute("TRUNCATE " + cf);
    }
  }
}
