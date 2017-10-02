CREATE KEYSPACE IF NOT EXISTS zipkin2_cassandra3 WITH replication = {'class': 'SimpleStrategy', 'replication_factor': '1'};

CREATE TYPE IF NOT EXISTS zipkin2_cassandra3.endpoint (
    service      text,
    ipv4         inet,
    ipv6         inet,
    port         smallint,
);

CREATE TYPE IF NOT EXISTS zipkin2_cassandra3.annotation (
    ts bigint,
    v  text,
);

CREATE TABLE IF NOT EXISTS zipkin2_cassandra3.traces (
    trace_id            text,
    ts_uuid             timeuuid,
    id                  text,
    ts                  bigint,
    span                text,
    parent_id           bigint,
    duration            bigint,
    l_ep                Endpoint,
    l_service           text,
    r_rp                Endpoint,
    annotations         list<frozen<annotation>>,
    tags                map<text,text>,
    shared              boolean,
    annotation_query    text, //-- can't do SASI on set<text>: comma-joined until CASSANDRA-11182
    PRIMARY KEY (trace_id, ts_uuid, id)
)
    WITH CLUSTERING ORDER BY (ts_uuid DESC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.TimeWindowCompactionStrategy'}
    AND default_time_to_live =  604800;


CREATE TABLE IF NOT EXISTS zipkin2_cassandra3.trace_by_service_span (
    service       text,             //-- service name
    span          text,             //-- span name, or blank for queries without span name
    bucket        int,              //-- time bucket, calculated as ts/interval (in microseconds), for some pre-configured interval like 1 day.
    ts            timeuuid,         //-- start timestamp of the span, truncated to millisecond precision
    trace_id      text,             //-- trace ID
    duration      bigint,           //-- span duration, in microseconds
    PRIMARY KEY ((service, span, bucket), ts)
)
   WITH CLUSTERING ORDER BY (ts DESC)
    AND compaction = {'class': 'org.apache.cassandra.db.compaction.TimeWindowCompactionStrategy'}
    AND default_time_to_live =  259200;

CREATE TABLE IF NOT EXISTS zipkin2_cassandra3.span_by_service (
    service text,
    span    text,
    PRIMARY KEY (service, span)
)
    WITH compaction = {'class': 'org.apache.cassandra.db.compaction.LeveledCompactionStrategy', 'unchecked_tombstone_compaction': 'true', 'tombstone_threshold': '0.2'}
    AND default_time_to_live =  259200;

CREATE CUSTOM INDEX IF NOT EXISTS ON zipkin2_cassandra3.traces (annotation_query) USING 'org.apache.cassandra.index.sasi.SASIIndex'
   WITH OPTIONS = {
    'mode': 'CONTAINS',
    'analyzed': 'true',
    'analyzer_class':'org.apache.cassandra.index.sasi.analyzer.NonTokenizingAnalyzer',
    'case_sensitive': 'false'
   };

CREATE CUSTOM INDEX IF NOT EXISTS ON zipkin2_cassandra3.traces (local_service) USING 'org.apache.cassandra.index.sasi.SASIIndex'
   WITH OPTIONS = {'mode': 'PREFIX'};

CREATE CUSTOM INDEX IF NOT EXISTS ON zipkin2_cassandra3.trace_by_service_span (duration) USING 'org.apache.cassandra.index.sasi.SASIIndex'
   WITH OPTIONS = {'mode': 'PREFIX'};


CREATE TABLE IF NOT EXISTS zipkin2_cassandra3.dependencies (
    day          timestamp,
    links        text,
    PRIMARY KEY (day)
)
    WITH compaction = {'class': 'org.apache.cassandra.db.compaction.LeveledCompactionStrategy', 'unchecked_tombstone_compaction': 'true', 'tombstone_threshold': '0.2'}
    AND default_time_to_live =  259200;
