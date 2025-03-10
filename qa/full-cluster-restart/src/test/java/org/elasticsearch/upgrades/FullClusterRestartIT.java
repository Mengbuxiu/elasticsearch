/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.upgrades;

import org.apache.http.util.EntityUtils;
import org.elasticsearch.Version;
import org.elasticsearch.action.admin.cluster.settings.ClusterGetSettingsResponse;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.MetadataIndexStateService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.time.DateUtils;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.core.Booleans;
import org.elasticsearch.core.CheckedFunction;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.mapper.DateFieldMapper;
import org.elasticsearch.rest.action.admin.indices.RestPutIndexTemplateAction;
import org.elasticsearch.test.NotEqualMessageBuilder;
import org.elasticsearch.test.XContentTestUtils;
import org.elasticsearch.test.rest.ESRestTestCase;
import org.elasticsearch.test.rest.yaml.ObjectPath;
import org.elasticsearch.transport.Compression;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.json.JsonXContent;
import org.junit.Before;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static io.github.nik9000.mapmatcher.MapMatcher.assertMap;
import static io.github.nik9000.mapmatcher.MapMatcher.matchesMap;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.toList;
import static org.elasticsearch.cluster.metadata.IndexNameExpressionResolver.SYSTEM_INDEX_ENFORCEMENT_VERSION;
import static org.elasticsearch.cluster.routing.UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING;
import static org.elasticsearch.cluster.routing.allocation.decider.MaxRetryAllocationDecider.SETTING_ALLOCATION_MAX_RETRY;
import static org.elasticsearch.transport.RemoteClusterService.REMOTE_CLUSTER_COMPRESS;
import static org.elasticsearch.xcontent.XContentFactory.jsonBuilder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests to run before and after a full cluster restart. This is run twice,
 * one with {@code tests.is_old_cluster} set to {@code true} against a cluster
 * of an older version. The cluster is shutdown and a cluster of the new
 * version is started with the same data directories and then this is rerun
 * with {@code tests.is_old_cluster} set to {@code false}.
 */
public class FullClusterRestartIT extends AbstractFullClusterRestartTestCase {

    private String index;

    @Before
    public void setIndex() {
        index = getTestName().toLowerCase(Locale.ROOT);
    }

    public void testSearch() throws Exception {
        int count;
        if (isRunningAgainstOldCluster()) {
            XContentBuilder mappingsAndSettings = jsonBuilder();
            mappingsAndSettings.startObject();
            {
                mappingsAndSettings.startObject("settings");
                mappingsAndSettings.field("number_of_shards", 1);
                mappingsAndSettings.field("number_of_replicas", 0);
                mappingsAndSettings.endObject();
            }
            {
                mappingsAndSettings.startObject("mappings");
                mappingsAndSettings.startObject("properties");
                {
                    mappingsAndSettings.startObject("string");
                    mappingsAndSettings.field("type", "text");
                    mappingsAndSettings.endObject();
                }
                {
                    mappingsAndSettings.startObject("dots_in_field_names");
                    mappingsAndSettings.field("type", "text");
                    mappingsAndSettings.endObject();
                }
                {
                    mappingsAndSettings.startObject("binary");
                    mappingsAndSettings.field("type", "binary");
                    mappingsAndSettings.field("store", "true");
                    mappingsAndSettings.endObject();
                }
                mappingsAndSettings.endObject();
                mappingsAndSettings.endObject();
            }
            mappingsAndSettings.endObject();

            Request createIndex = new Request("PUT", "/" + index);
            createIndex.setJsonEntity(Strings.toString(mappingsAndSettings));
            client().performRequest(createIndex);

            count = randomIntBetween(2000, 3000);
            byte[] randomByteArray = new byte[16];
            random().nextBytes(randomByteArray);
            indexRandomDocuments(
                count,
                true,
                true,
                randomBoolean(),
                i -> JsonXContent.contentBuilder()
                    .startObject()
                    .field("string", randomAlphaOfLength(10))
                    .field("int", randomInt(100))
                    .field("float", randomFloat())
                    // be sure to create a "proper" boolean (True, False) for the first document so that automapping is correct
                    .field("bool", i > 0 && randomBoolean())
                    .field("field.with.dots", randomAlphaOfLength(10))
                    .field("binary", Base64.getEncoder().encodeToString(randomByteArray))
                    .endObject()
            );
            refreshAllIndices();
        } else {
            count = countOfIndexedRandomDocuments();
        }

        ensureGreenLongWait(index);
        assertBasicSearchWorks(count);
        assertAllSearchWorks(count);
        assertBasicAggregationWorks();
        assertRealtimeGetWorks();
        assertStoredBinaryFields(count);
    }

    public void testNewReplicas() throws Exception {
        if (isRunningAgainstOldCluster()) {
            XContentBuilder mappingsAndSettings = jsonBuilder();
            mappingsAndSettings.startObject();
            {
                mappingsAndSettings.startObject("settings");
                mappingsAndSettings.field("number_of_shards", 1);
                mappingsAndSettings.field("number_of_replicas", 0);
                mappingsAndSettings.endObject();
            }
            {
                mappingsAndSettings.startObject("mappings");
                mappingsAndSettings.startObject("properties");
                {
                    mappingsAndSettings.startObject("field");
                    mappingsAndSettings.field("type", "text");
                    mappingsAndSettings.endObject();
                }
                mappingsAndSettings.endObject();
                mappingsAndSettings.endObject();
            }
            mappingsAndSettings.endObject();

            Request createIndex = new Request("PUT", "/" + index);
            createIndex.setJsonEntity(Strings.toString(mappingsAndSettings));
            client().performRequest(createIndex);

            int numDocs = randomIntBetween(2000, 3000);
            indexRandomDocuments(
                numDocs,
                true,
                false,
                randomBoolean(),
                i -> JsonXContent.contentBuilder().startObject().field("field", "value").endObject()
            );
            logger.info("Refreshing [{}]", index);
            client().performRequest(new Request("POST", "/" + index + "/_refresh"));
        } else {
            // The test runs with two nodes so this should still go green.
            final int numReplicas = 1;
            final long startTime = System.currentTimeMillis();
            logger.debug("--> creating [{}] replicas for index [{}]", numReplicas, index);
            Request setNumberOfReplicas = new Request("PUT", "/" + index + "/_settings");
            setNumberOfReplicas.setJsonEntity("{ \"index\": { \"number_of_replicas\" : " + numReplicas + " }}");
            client().performRequest(setNumberOfReplicas);

            ensureGreenLongWait(index);

            logger.debug("--> index [{}] is green, took [{}] ms", index, (System.currentTimeMillis() - startTime));
            Map<String, Object> recoverRsp = entityAsMap(client().performRequest(new Request("GET", "/" + index + "/_recovery")));
            logger.debug("--> recovery status:\n{}", recoverRsp);

            Set<Integer> counts = new HashSet<>();
            for (String node : dataNodes(index, client())) {
                Request search = new Request("GET", "/" + index + "/_search");
                search.addParameter("preference", "_only_nodes:" + node);
                Map<String, Object> responseBody = entityAsMap(client().performRequest(search));
                assertNoFailures(responseBody);
                int hits = extractTotalHits(responseBody);
                counts.add(hits);
            }
            assertEquals("All nodes should have a consistent number of documents", 1, counts.size());
        }
    }

    public void testSearchTimeSeriesMode() throws Exception {
        assumeTrue("time series index sort by _tsid introduced in 8.1.0", getOldClusterVersion().onOrAfter(Version.V_8_1_0));
        int numDocs;
        if (isRunningAgainstOldCluster()) {
            numDocs = createTimeSeriesModeIndex(1);
        } else {
            numDocs = countOfIndexedRandomDocuments();
        }
        assertCountAll(numDocs);
        Request request = new Request("GET", "/" + index + "/_search");
        XContentBuilder body = jsonBuilder().startObject();
        body.field("size", 0);
        body.startObject("aggs").startObject("check").startObject("scripted_metric");
        {
            body.field("init_script", "state.timeSeries = new HashSet()");
            body.field("map_script", "state.timeSeries.add(doc['dim'].value)");
            body.field("combine_script", "return state.timeSeries");
            StringBuilder reduceScript = new StringBuilder();
            reduceScript.append("Set timeSeries = new TreeSet();");
            reduceScript.append("for (s in states) {");
            reduceScript.append("  for (ts in s) {");
            reduceScript.append("    boolean newTs = timeSeries.add(ts);");
            reduceScript.append("    if (false == newTs) {");
            reduceScript.append("      throw new IllegalArgumentException(ts + ' appeared in two shards');");
            reduceScript.append("    }");
            reduceScript.append("  }");
            reduceScript.append("}");
            reduceScript.append("return timeSeries;");
            body.field("reduce_script", reduceScript.toString());
        }
        body.endObject().endObject().endObject();
        body.endObject();
        request.setJsonEntity(Strings.toString(body));
        Map<String, Object> response = entityAsMap(client().performRequest(request));
        assertMap(
            response,
            matchesMap().extraOk()
                .entry("hits", matchesMap().extraOk().entry("total", Map.of("value", numDocs, "relation", "eq")))
                .entry("aggregations", Map.of("check", Map.of("value", IntStream.range(0, 10).mapToObj(i -> "dim" + i).collect(toList()))))
        );
    }

    public void testNewReplicasTimeSeriesMode() throws Exception {
        assumeTrue("time series index sort by _tsid introduced in 8.1.0", getOldClusterVersion().onOrAfter(Version.V_8_1_0));
        if (isRunningAgainstOldCluster()) {
            createTimeSeriesModeIndex(0);
        } else {
            // The test runs with two nodes so this should still go green.
            final int numReplicas = 1;
            final long startTime = System.currentTimeMillis();
            logger.debug("--> creating [{}] replicas for index [{}]", numReplicas, index);
            Request setNumberOfReplicas = new Request("PUT", "/" + index + "/_settings");
            setNumberOfReplicas.setJsonEntity("{ \"index\": { \"number_of_replicas\" : " + numReplicas + " }}");
            client().performRequest(setNumberOfReplicas);

            ensureGreenLongWait(index);

            logger.debug("--> index [{}] is green, took [{}] ms", index, (System.currentTimeMillis() - startTime));
            Map<String, Object> recoverRsp = entityAsMap(client().performRequest(new Request("GET", "/" + index + "/_recovery")));
            logger.debug("--> recovery status:\n{}", recoverRsp);

            Set<Integer> counts = new HashSet<>();
            for (String node : dataNodes(index, client())) {
                Request search = new Request("GET", "/" + index + "/_search");
                search.addParameter("preference", "_only_nodes:" + node);
                Map<String, Object> responseBody = entityAsMap(client().performRequest(search));
                assertNoFailures(responseBody);
                int hits = extractTotalHits(responseBody);
                counts.add(hits);
            }
            assertEquals("All nodes should have a consistent number of documents", 1, counts.size());
        }
    }

    private int createTimeSeriesModeIndex(int replicas) throws IOException {
        XContentBuilder mappingsAndSettings = jsonBuilder();
        mappingsAndSettings.startObject();
        {
            mappingsAndSettings.startObject("settings");
            mappingsAndSettings.field("number_of_shards", 1);
            mappingsAndSettings.field("number_of_replicas", replicas);
            mappingsAndSettings.field("mode", "time_series");
            mappingsAndSettings.field("routing_path", "dim");
            mappingsAndSettings.field("time_series.start_time", 1L);
            mappingsAndSettings.field("time_series.end_time", DateUtils.MAX_MILLIS_BEFORE_9999 - 1);
            mappingsAndSettings.endObject();
        }
        {
            mappingsAndSettings.startObject("mappings");
            mappingsAndSettings.startObject("properties");
            {
                mappingsAndSettings.startObject("@timestamp").field("type", "date").endObject();
                mappingsAndSettings.startObject("dim").field("type", "keyword").field("time_series_dimension", true).endObject();
            }
            mappingsAndSettings.endObject();
            mappingsAndSettings.endObject();
        }
        mappingsAndSettings.endObject();

        Request createIndex = new Request("PUT", "/" + index);
        createIndex.setJsonEntity(Strings.toString(mappingsAndSettings));
        client().performRequest(createIndex);

        int numDocs = randomIntBetween(2000, 3000);
        long basetime = DateFieldMapper.DEFAULT_DATE_TIME_FORMATTER.parseMillis("2021-01-01T00:00:00Z");
        indexRandomDocuments(
            numDocs,
            true,
            true,
            false,
            i -> JsonXContent.contentBuilder()
                .startObject()
                .field("@timestamp", basetime + TimeUnit.MINUTES.toMillis(i))
                .field("dim", "dim" + (i % 10))
                .endObject()
        );
        logger.info("Refreshing [{}]", index);
        client().performRequest(new Request("POST", "/" + index + "/_refresh"));
        return numDocs;
    }

    public void testClusterState() throws Exception {
        if (isRunningAgainstOldCluster()) {
            XContentBuilder mappingsAndSettings = jsonBuilder();
            mappingsAndSettings.startObject();
            mappingsAndSettings.field("index_patterns", index);
            mappingsAndSettings.field("order", "1000");
            {
                mappingsAndSettings.startObject("settings");
                mappingsAndSettings.field("number_of_shards", 1);
                mappingsAndSettings.field("number_of_replicas", 0);
                mappingsAndSettings.endObject();
            }
            mappingsAndSettings.endObject();
            Request createTemplate = new Request("PUT", "/_template/template_1");
            createTemplate.setJsonEntity(Strings.toString(mappingsAndSettings));
            createTemplate.setOptions(expectWarnings(RestPutIndexTemplateAction.DEPRECATION_WARNING));
            client().performRequest(createTemplate);
            client().performRequest(new Request("PUT", "/" + index));
        }

        // verifying if we can still read some properties from cluster state api:
        Map<String, Object> clusterState = entityAsMap(client().performRequest(new Request("GET", "/_cluster/state")));

        // Check some global properties:
        String numberOfShards = (String) XContentMapValues.extractValue(
            "metadata.templates.template_1.settings.index.number_of_shards",
            clusterState
        );
        assertEquals("1", numberOfShards);
        String numberOfReplicas = (String) XContentMapValues.extractValue(
            "metadata.templates.template_1.settings.index.number_of_replicas",
            clusterState
        );
        assertEquals("0", numberOfReplicas);

        // Check some index properties:
        numberOfShards = (String) XContentMapValues.extractValue(
            "metadata.indices." + index + ".settings.index.number_of_shards",
            clusterState
        );
        assertEquals("1", numberOfShards);
        numberOfReplicas = (String) XContentMapValues.extractValue(
            "metadata.indices." + index + ".settings.index.number_of_replicas",
            clusterState
        );
        assertEquals("0", numberOfReplicas);
        Version version = Version.fromId(
            Integer.valueOf(
                (String) XContentMapValues.extractValue("metadata.indices." + index + ".settings.index.version.created", clusterState)
            )
        );
        assertEquals(getOldClusterVersion(), version);

    }

    public void testShrink() throws IOException {
        String shrunkenIndex = index + "_shrunk";
        int numDocs;
        if (isRunningAgainstOldCluster()) {
            XContentBuilder mappingsAndSettings = jsonBuilder();
            mappingsAndSettings.startObject();
            {
                mappingsAndSettings.startObject("mappings");
                {
                    mappingsAndSettings.startObject("properties");
                    {
                        mappingsAndSettings.startObject("field");
                        {
                            mappingsAndSettings.field("type", "text");
                        }
                        mappingsAndSettings.endObject();
                    }
                    mappingsAndSettings.endObject();
                }
                mappingsAndSettings.endObject();

                mappingsAndSettings.startObject("settings");
                {
                    mappingsAndSettings.field("index.number_of_shards", 5);
                }
                mappingsAndSettings.endObject();
            }
            mappingsAndSettings.endObject();

            Request createIndex = new Request("PUT", "/" + index);
            createIndex.setJsonEntity(Strings.toString(mappingsAndSettings));
            client().performRequest(createIndex);

            numDocs = randomIntBetween(512, 1024);
            indexRandomDocuments(
                numDocs,
                true,
                true,
                randomBoolean(),
                i -> JsonXContent.contentBuilder().startObject().field("field", "value").endObject()
            );

            ensureGreen(index); // wait for source index to be available on both nodes before starting shrink

            Request updateSettingsRequest = new Request("PUT", "/" + index + "/_settings");
            updateSettingsRequest.setJsonEntity("{\"settings\": {\"index.blocks.write\": true}}");
            client().performRequest(updateSettingsRequest);

            Request shrinkIndexRequest = new Request("PUT", "/" + index + "/_shrink/" + shrunkenIndex);

            shrinkIndexRequest.setJsonEntity("{\"settings\": {\"index.number_of_shards\": 1}}");
            client().performRequest(shrinkIndexRequest);

            refreshAllIndices();
        } else {
            numDocs = countOfIndexedRandomDocuments();
        }

        Map<?, ?> response = entityAsMap(client().performRequest(new Request("GET", "/" + index + "/_search")));
        assertNoFailures(response);
        int totalShards = (int) XContentMapValues.extractValue("_shards.total", response);
        assertThat(totalShards, greaterThan(1));
        int successfulShards = (int) XContentMapValues.extractValue("_shards.successful", response);
        assertEquals(totalShards, successfulShards);
        int totalHits = extractTotalHits(response);
        assertEquals(numDocs, totalHits);

        response = entityAsMap(client().performRequest(new Request("GET", "/" + shrunkenIndex + "/_search")));
        assertNoFailures(response);
        totalShards = (int) XContentMapValues.extractValue("_shards.total", response);
        assertEquals(1, totalShards);
        successfulShards = (int) XContentMapValues.extractValue("_shards.successful", response);
        assertEquals(1, successfulShards);
        totalHits = extractTotalHits(response);
        assertEquals(numDocs, totalHits);
    }

    public void testShrinkAfterUpgrade() throws IOException {
        String shrunkenIndex = index + "_shrunk";
        int numDocs;
        if (isRunningAgainstOldCluster()) {
            XContentBuilder mappingsAndSettings = jsonBuilder();
            mappingsAndSettings.startObject();
            {
                mappingsAndSettings.startObject("mappings");
                {
                    mappingsAndSettings.startObject("properties");
                    {
                        mappingsAndSettings.startObject("field");
                        {
                            mappingsAndSettings.field("type", "text");
                        }
                        mappingsAndSettings.endObject();
                    }
                    mappingsAndSettings.endObject();
                }
                mappingsAndSettings.endObject();
                // the default number of shards is now one so we have to set the number of shards to be more than one explicitly
                mappingsAndSettings.startObject("settings");
                mappingsAndSettings.field("index.number_of_shards", 5);
                mappingsAndSettings.endObject();
            }
            mappingsAndSettings.endObject();

            Request createIndex = new Request("PUT", "/" + index);
            createIndex.setJsonEntity(Strings.toString(mappingsAndSettings));
            client().performRequest(createIndex);

            numDocs = randomIntBetween(512, 1024);
            indexRandomDocuments(
                numDocs,
                true,
                true,
                randomBoolean(),
                i -> JsonXContent.contentBuilder().startObject().field("field", "value").endObject()
            );
        } else {
            ensureGreen(index); // wait for source index to be available on both nodes before starting shrink

            Request updateSettingsRequest = new Request("PUT", "/" + index + "/_settings");
            updateSettingsRequest.setJsonEntity("{\"settings\": {\"index.blocks.write\": true}}");
            client().performRequest(updateSettingsRequest);

            Request shrinkIndexRequest = new Request("PUT", "/" + index + "/_shrink/" + shrunkenIndex);
            shrinkIndexRequest.setJsonEntity("{\"settings\": {\"index.number_of_shards\": 1}}");
            client().performRequest(shrinkIndexRequest);

            numDocs = countOfIndexedRandomDocuments();
        }

        refreshAllIndices();

        Map<?, ?> response = entityAsMap(client().performRequest(new Request("GET", "/" + index + "/_search")));
        assertNoFailures(response);
        int totalShards = (int) XContentMapValues.extractValue("_shards.total", response);
        assertThat(totalShards, greaterThan(1));
        int successfulShards = (int) XContentMapValues.extractValue("_shards.successful", response);
        assertEquals(totalShards, successfulShards);
        int totalHits = extractTotalHits(response);
        assertEquals(numDocs, totalHits);

        if (isRunningAgainstOldCluster() == false) {
            response = entityAsMap(client().performRequest(new Request("GET", "/" + shrunkenIndex + "/_search")));
            assertNoFailures(response);
            totalShards = (int) XContentMapValues.extractValue("_shards.total", response);
            assertEquals(1, totalShards);
            successfulShards = (int) XContentMapValues.extractValue("_shards.successful", response);
            assertEquals(1, successfulShards);
            totalHits = extractTotalHits(response);
            assertEquals(numDocs, totalHits);
        }
    }

    /**
     * Test upgrading after a rollover. Specifically:
     * <ol>
     *  <li>Create an index with a write alias
     *  <li>Write some documents to the write alias
     *  <li>Roll over the index
     *  <li>Make sure the document count is correct
     *  <li>Upgrade
     *  <li>Write some more documents to the write alias
     *  <li>Make sure the document count is correct
     * </ol>
     */
    public void testRollover() throws IOException {
        if (isRunningAgainstOldCluster()) {
            Request createIndex = new Request("PUT", "/" + index + "-000001");
            createIndex.setJsonEntity("{" + "  \"aliases\": {" + "    \"" + index + "_write\": {}" + "  }" + "}");
            client().performRequest(createIndex);
        }

        int bulkCount = 10;
        StringBuilder bulk = new StringBuilder();
        for (int i = 0; i < bulkCount; i++) {
            bulk.append("{\"index\":{}}\n");
            bulk.append("{\"test\":\"test\"}\n");
        }

        Request bulkRequest = new Request("POST", "/" + index + "_write/_bulk");

        bulkRequest.setJsonEntity(bulk.toString());
        bulkRequest.addParameter("refresh", "");
        assertThat(EntityUtils.toString(client().performRequest(bulkRequest).getEntity()), containsString("\"errors\":false"));

        if (isRunningAgainstOldCluster()) {
            Request rolloverRequest = new Request("POST", "/" + index + "_write/_rollover");
            rolloverRequest.setJsonEntity("{" + "  \"conditions\": {" + "    \"max_docs\": 5" + "  }" + "}");
            client().performRequest(rolloverRequest);

            assertThat(
                EntityUtils.toString(client().performRequest(new Request("GET", "/_cat/indices?v")).getEntity()),
                containsString("testrollover-000002")
            );
        }

        Request countRequest = new Request("POST", "/" + index + "-*/_search");
        countRequest.addParameter("size", "0");
        Map<String, Object> count = entityAsMap(client().performRequest(countRequest));
        assertNoFailures(count);

        int expectedCount = bulkCount + (isRunningAgainstOldCluster() ? 0 : bulkCount);
        assertEquals(expectedCount, extractTotalHits(count));
    }

    void assertCountAll(int count) throws IOException {
        Map<String, Object> response = entityAsMap(client().performRequest(new Request("GET", "/" + index + "/_search")));
        assertNoFailures(response);
        int numDocs = extractTotalHits(response);
        logger.info("Found {} in old index", numDocs);
        assertEquals(count, numDocs);
    }

    void assertBasicSearchWorks(int count) throws IOException {
        logger.info("--> testing basic search");
        {
            assertCountAll(count);
        }

        logger.info("--> testing basic search with sort");
        {
            Request searchRequest = new Request("GET", "/" + index + "/_search");
            searchRequest.setJsonEntity("{ \"sort\": [{ \"int\" : \"asc\" }]}");
            Map<String, Object> response = entityAsMap(client().performRequest(searchRequest));
            assertNoFailures(response);
            assertTotalHits(count, response);
        }

        logger.info("--> testing exists filter");
        {
            Request searchRequest = new Request("GET", "/" + index + "/_search");
            searchRequest.setJsonEntity("{ \"query\": { \"exists\" : {\"field\": \"string\"} }}");
            Map<String, Object> response = entityAsMap(client().performRequest(searchRequest));
            assertNoFailures(response);
            assertTotalHits(count, response);
        }

        logger.info("--> testing field with dots in the name");
        {
            Request searchRequest = new Request("GET", "/" + index + "/_search");
            searchRequest.setJsonEntity("{ \"query\": { \"exists\" : {\"field\": \"field.with.dots\"} }}");
            Map<String, Object> response = entityAsMap(client().performRequest(searchRequest));
            assertNoFailures(response);
            assertTotalHits(count, response);
        }
    }

    void assertAllSearchWorks(int count) throws IOException {
        logger.info("--> testing _all search");
        Map<String, Object> response = entityAsMap(client().performRequest(new Request("GET", "/" + index + "/_search")));
        assertNoFailures(response);
        assertTotalHits(count, response);
        Map<?, ?> bestHit = (Map<?, ?>) ((List<?>) (XContentMapValues.extractValue("hits.hits", response))).get(0);

        // Make sure there are payloads and they are taken into account for the score
        // the 'string' field has a boost of 4 in the mappings so it should get a payload boost
        String stringValue = (String) XContentMapValues.extractValue("_source.string", bestHit);
        assertNotNull(stringValue);
        String id = (String) bestHit.get("_id");

        Request explainRequest = new Request("GET", "/" + index + "/_explain/" + id);
        explainRequest.setJsonEntity("{ \"query\": { \"match_all\" : {} }}");
        String explanation = toStr(client().performRequest(explainRequest));
        assertFalse("Could not find payload boost in explanation\n" + explanation, explanation.contains("payloadBoost"));

        // Make sure the query can run on the whole index
        Request searchRequest = new Request("GET", "/" + index + "/_search");
        searchRequest.setEntity(explainRequest.getEntity());
        searchRequest.addParameter("explain", "true");
        Map<?, ?> matchAllResponse = entityAsMap(client().performRequest(searchRequest));
        assertNoFailures(matchAllResponse);
        assertTotalHits(count, matchAllResponse);
    }

    void assertBasicAggregationWorks() throws IOException {
        // histogram on a long
        Request longHistogramRequest = new Request("GET", "/" + index + "/_search");
        longHistogramRequest.setJsonEntity("{ \"aggs\": { \"histo\" : {\"histogram\" : {\"field\": \"int\", \"interval\": 10}} }}");
        Map<?, ?> longHistogram = entityAsMap(client().performRequest(longHistogramRequest));
        assertNoFailures(longHistogram);
        List<?> histoBuckets = (List<?>) XContentMapValues.extractValue("aggregations.histo.buckets", longHistogram);
        int histoCount = 0;
        for (Object entry : histoBuckets) {
            Map<?, ?> bucket = (Map<?, ?>) entry;
            histoCount += (Integer) bucket.get("doc_count");
        }
        assertTotalHits(histoCount, longHistogram);

        // terms on a boolean
        Request boolTermsRequest = new Request("GET", "/" + index + "/_search");
        boolTermsRequest.setJsonEntity("{ \"aggs\": { \"bool_terms\" : {\"terms\" : {\"field\": \"bool\"}} }}");
        Map<?, ?> boolTerms = entityAsMap(client().performRequest(boolTermsRequest));
        List<?> termsBuckets = (List<?>) XContentMapValues.extractValue("aggregations.bool_terms.buckets", boolTerms);
        int termsCount = 0;
        for (Object entry : termsBuckets) {
            Map<?, ?> bucket = (Map<?, ?>) entry;
            termsCount += (Integer) bucket.get("doc_count");
        }
        assertTotalHits(termsCount, boolTerms);
    }

    void assertRealtimeGetWorks() throws IOException {
        Request disableAutoRefresh = new Request("PUT", "/" + index + "/_settings");
        disableAutoRefresh.setJsonEntity("{ \"index\": { \"refresh_interval\" : -1 }}");
        client().performRequest(disableAutoRefresh);

        Request searchRequest = new Request("GET", "/" + index + "/_search");
        searchRequest.setJsonEntity("{ \"query\": { \"match_all\" : {} }}");
        Map<?, ?> searchResponse = entityAsMap(client().performRequest(searchRequest));
        Map<?, ?> hit = (Map<?, ?>) ((List<?>) (XContentMapValues.extractValue("hits.hits", searchResponse))).get(0);
        String docId = (String) hit.get("_id");

        Request updateRequest = new Request("POST", "/" + index + "/_update/" + docId);
        updateRequest.setJsonEntity("{ \"doc\" : { \"foo\": \"bar\"}}");
        client().performRequest(updateRequest);

        Request getRequest = new Request("GET", "/" + index + "/_doc/" + docId);

        Map<String, Object> getRsp = entityAsMap(client().performRequest(getRequest));
        Map<?, ?> source = (Map<?, ?>) getRsp.get("_source");
        assertTrue("doc does not contain 'foo' key: " + source, source.containsKey("foo"));

        Request enableAutoRefresh = new Request("PUT", "/" + index + "/_settings");
        enableAutoRefresh.setJsonEntity("{ \"index\": { \"refresh_interval\" : \"1s\" }}");
        client().performRequest(enableAutoRefresh);
    }

    void assertStoredBinaryFields(int count) throws Exception {
        Request request = new Request("GET", "/" + index + "/_search");
        request.setJsonEntity("{ \"query\": { \"match_all\" : {} }, \"size\": 100, \"stored_fields\": \"binary\"}");
        Map<String, Object> rsp = entityAsMap(client().performRequest(request));

        assertTotalHits(count, rsp);
        List<?> hits = (List<?>) XContentMapValues.extractValue("hits.hits", rsp);
        assertEquals(100, hits.size());
        for (Object hit : hits) {
            Map<?, ?> hitRsp = (Map<?, ?>) hit;
            List<?> values = (List<?>) XContentMapValues.extractValue("fields.binary", hitRsp);
            assertEquals(1, values.size());
            String value = (String) values.get(0);
            byte[] binaryValue = Base64.getDecoder().decode(value);
            assertEquals("Unexpected string length [" + value + "]", 16, binaryValue.length);
        }
    }

    static String toStr(Response response) throws IOException {
        return EntityUtils.toString(response.getEntity());
    }

    static void assertNoFailures(Map<?, ?> response) {
        int failed = (int) XContentMapValues.extractValue("_shards.failed", response);
        assertEquals(0, failed);
    }

    void assertTotalHits(int expectedTotalHits, Map<?, ?> response) {
        int actualTotalHits = extractTotalHits(response);
        assertEquals(response.toString(), expectedTotalHits, actualTotalHits);
    }

    static int extractTotalHits(Map<?, ?> response) {
        return (Integer) XContentMapValues.extractValue("hits.total.value", response);
    }

    /**
     * Tests that a single document survives. Super basic smoke test.
     */
    public void testSingleDoc() throws IOException {
        String docLocation = "/" + index + "/_doc/1";
        String doc = "{\"test\": \"test\"}";

        if (isRunningAgainstOldCluster()) {
            Request createDoc = new Request("PUT", docLocation);
            createDoc.setJsonEntity(doc);
            client().performRequest(createDoc);
        }

        Request request = new Request("GET", docLocation);
        assertThat(toStr(client().performRequest(request)), containsString(doc));
    }

    /**
     * Tests that a single empty shard index is correctly recovered. Empty shards are often an edge case.
     */
    public void testEmptyShard() throws IOException {
        final String indexName = "test_empty_shard";

        if (isRunningAgainstOldCluster()) {
            Settings.Builder settings = Settings.builder()
                .put(IndexMetadata.INDEX_NUMBER_OF_SHARDS_SETTING.getKey(), 1)
                .put(IndexMetadata.INDEX_NUMBER_OF_REPLICAS_SETTING.getKey(), 1)
                // if the node with the replica is the first to be restarted, while a replica is still recovering
                // then delayed allocation will kick in. When the node comes back, the master will search for a copy
                // but the recovering copy will be seen as invalid and the cluster health won't return to GREEN
                // before timing out
                .put(INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING.getKey(), "100ms")
                .put(SETTING_ALLOCATION_MAX_RETRY.getKey(), "0"); // fail faster
            createIndex(indexName, settings.build());
        }
        ensureGreen(indexName);
    }

    /**
     * Tests recovery of an index with or without a translog and the
     * statistics we gather about that.
     */
    public void testRecovery() throws Exception {
        int count;
        boolean shouldHaveTranslog;
        if (isRunningAgainstOldCluster()) {
            count = between(200, 300);
            /* We've had bugs in the past where we couldn't restore
             * an index without a translog so we randomize whether
             * or not we have one. */
            shouldHaveTranslog = randomBoolean();
            Settings.Builder settings = Settings.builder();
            if (minimumNodeVersion().before(Version.V_8_0_0) && randomBoolean()) {
                settings.put(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), randomBoolean());
            }
            final String mappings = randomBoolean() ? "\"_source\": { \"enabled\": false}" : null;
            createIndex(index, settings.build(), mappings);
            indexRandomDocuments(count, true, true, true, i -> jsonBuilder().startObject().field("field", "value").endObject());

            // make sure all recoveries are done
            ensureGreen(index);

            // Force flush so we're sure that all translog are committed
            Request flushRequest = new Request("POST", "/" + index + "/_flush");
            flushRequest.addParameter("force", "true");
            flushRequest.addParameter("wait_if_ongoing", "true");
            assertOK(client().performRequest(flushRequest));

            if (shouldHaveTranslog) {
                // Update a few documents so we are sure to have a translog
                indexRandomDocuments(
                    count / 10,
                    false, // flushing here would invalidate the whole thing
                    false,
                    true,
                    i -> jsonBuilder().startObject().field("field", "value").endObject()
                );
            }
            saveInfoDocument(index + "_should_have_translog", Boolean.toString(shouldHaveTranslog));
        } else {
            count = countOfIndexedRandomDocuments();
            shouldHaveTranslog = Booleans.parseBoolean(loadInfoDocument(index + "_should_have_translog"));
        }

        // Count the documents in the index to make sure we have as many as we put there
        Request countRequest = new Request("GET", "/" + index + "/_search");
        countRequest.addParameter("size", "0");
        refreshAllIndices();
        Map<String, Object> countResponse = entityAsMap(client().performRequest(countRequest));
        assertTotalHits(count, countResponse);

        if (false == isRunningAgainstOldCluster()) {
            boolean restoredFromTranslog = false;
            boolean foundPrimary = false;
            Request recoveryRequest = new Request("GET", "/_cat/recovery/" + index);
            recoveryRequest.addParameter("h", "index,shard,type,stage,translog_ops_recovered");
            recoveryRequest.addParameter("s", "index,shard,type");
            String recoveryResponse = toStr(client().performRequest(recoveryRequest));
            for (String line : recoveryResponse.split("\n")) {
                // Find the primaries
                foundPrimary = true;
                if (false == line.contains("done") && line.contains("existing_store")) {
                    continue;
                }
                /* Mark if we see a primary that looked like it restored from the translog.
                 * Not all primaries will look like this all the time because we modify
                 * random documents when we want there to be a translog and they might
                 * not be spread around all the shards. */
                Matcher m = Pattern.compile("(\\d+)$").matcher(line);
                assertTrue(line, m.find());
                int translogOps = Integer.parseInt(m.group(1));
                if (translogOps > 0) {
                    restoredFromTranslog = true;
                }
            }
            assertTrue("expected to find a primary but didn't\n" + recoveryResponse, foundPrimary);
            assertEquals("mismatch while checking for translog recovery\n" + recoveryResponse, shouldHaveTranslog, restoredFromTranslog);

            String currentLuceneVersion = Version.CURRENT.luceneVersion.toString();
            String bwcLuceneVersion = getOldClusterVersion().luceneVersion.toString();
            if (shouldHaveTranslog && false == currentLuceneVersion.equals(bwcLuceneVersion)) {
                int numCurrentVersion = 0;
                int numBwcVersion = 0;
                Request segmentsRequest = new Request("GET", "/_cat/segments/" + index);
                segmentsRequest.addParameter("h", "prirep,shard,index,version");
                segmentsRequest.addParameter("s", "prirep,shard,index");
                String segmentsResponse = toStr(client().performRequest(segmentsRequest));
                for (String line : segmentsResponse.split("\n")) {
                    if (false == line.startsWith("p")) {
                        continue;
                    }
                    Matcher m = Pattern.compile("(\\d+\\.\\d+\\.\\d+)$").matcher(line);
                    assertTrue(line, m.find());
                    String version = m.group(1);
                    if (currentLuceneVersion.equals(version)) {
                        numCurrentVersion++;
                    } else if (bwcLuceneVersion.equals(version)) {
                        numBwcVersion++;
                    } else {
                        fail("expected version to be one of [" + currentLuceneVersion + "," + bwcLuceneVersion + "] but was " + line);
                    }
                }
                assertNotEquals(
                    "expected at least 1 current segment after translog recovery. segments:\n" + segmentsResponse,
                    0,
                    numCurrentVersion
                );
                assertNotEquals("expected at least 1 old segment. segments:\n" + segmentsResponse, 0, numBwcVersion);
            }
        }
    }

    /**
     * Tests snapshot/restore by creating a snapshot and restoring it. It takes
     * a snapshot on the old cluster and restores it on the old cluster as a
     * sanity check and on the new cluster as an upgrade test. It also takes a
     * snapshot on the new cluster and restores that on the new cluster as a
     * test that the repository is ok with containing snapshot from both the
     * old and new versions. All of the snapshots include an index, a template,
     * and some routing configuration.
     */
    public void testSnapshotRestore() throws IOException {
        int count;
        if (isRunningAgainstOldCluster()) {
            // Create the index
            count = between(200, 300);
            Settings.Builder settings = Settings.builder();
            if (minimumNodeVersion().before(Version.V_8_0_0) && randomBoolean()) {
                settings.put(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), randomBoolean());
            }
            createIndex(index, settings.build());
            indexRandomDocuments(count, true, true, randomBoolean(), i -> jsonBuilder().startObject().field("field", "value").endObject());
        } else {
            count = countOfIndexedRandomDocuments();
        }

        // Refresh the index so the count doesn't fail
        refreshAllIndices();

        // Count the documents in the index to make sure we have as many as we put there
        Request countRequest = new Request("GET", "/" + index + "/_search");
        countRequest.addParameter("size", "0");
        Map<String, Object> countResponse = entityAsMap(client().performRequest(countRequest));
        assertTotalHits(count, countResponse);

        // Stick a routing attribute into to cluster settings so we can see it after the restore
        Request addRoutingSettings = new Request("PUT", "/_cluster/settings");
        addRoutingSettings.setJsonEntity(
            "{\"persistent\": {\"cluster.routing.allocation.exclude.test_attr\": \"" + getOldClusterVersion() + "\"}}"
        );
        client().performRequest(addRoutingSettings);

        // Stick a template into the cluster so we can see it after the restore
        XContentBuilder templateBuilder = JsonXContent.contentBuilder().startObject();
        templateBuilder.field("index_patterns", "evil_*"); // Don't confuse other tests by applying the template
        templateBuilder.startObject("settings");
        {
            templateBuilder.field("number_of_shards", 1);
        }
        templateBuilder.endObject();
        templateBuilder.startObject("mappings");
        {
            {
                templateBuilder.startObject("_source");
                {
                    templateBuilder.field("enabled", true);
                }
                templateBuilder.endObject();
            }
        }
        templateBuilder.endObject();
        templateBuilder.startObject("aliases");
        {
            templateBuilder.startObject("alias1").endObject();
            templateBuilder.startObject("alias2");
            {
                templateBuilder.startObject("filter");
                {
                    templateBuilder.startObject("term");
                    {
                        templateBuilder.field("version", isRunningAgainstOldCluster() ? getOldClusterVersion() : Version.CURRENT);
                    }
                    templateBuilder.endObject();
                }
                templateBuilder.endObject();
            }
            templateBuilder.endObject();
        }
        templateBuilder.endObject().endObject();
        Request createTemplateRequest = new Request("PUT", "/_template/test_template");
        createTemplateRequest.setJsonEntity(Strings.toString(templateBuilder));
        createTemplateRequest.setOptions(expectWarnings(RestPutIndexTemplateAction.DEPRECATION_WARNING));

        client().performRequest(createTemplateRequest);

        if (isRunningAgainstOldCluster()) {
            // Create the repo
            XContentBuilder repoConfig = JsonXContent.contentBuilder().startObject();
            {
                repoConfig.field("type", "fs");
                repoConfig.startObject("settings");
                {
                    repoConfig.field("compress", randomBoolean());
                    repoConfig.field("location", System.getProperty("tests.path.repo"));
                }
                repoConfig.endObject();
            }
            repoConfig.endObject();
            Request createRepoRequest = new Request("PUT", "/_snapshot/repo");
            createRepoRequest.setJsonEntity(Strings.toString(repoConfig));
            client().performRequest(createRepoRequest);
        }

        Request createSnapshot = new Request("PUT", "/_snapshot/repo/" + (isRunningAgainstOldCluster() ? "old_snap" : "new_snap"));
        createSnapshot.addParameter("wait_for_completion", "true");
        createSnapshot.setJsonEntity("{\"indices\": \"" + index + "\"}");
        client().performRequest(createSnapshot);

        checkSnapshot("old_snap", count, getOldClusterVersion());
        if (false == isRunningAgainstOldCluster()) {
            checkSnapshot("new_snap", count, Version.CURRENT);
        }
    }

    public void testHistoryUUIDIsAdded() throws Exception {
        if (isRunningAgainstOldCluster()) {
            XContentBuilder mappingsAndSettings = jsonBuilder();
            mappingsAndSettings.startObject();
            {
                mappingsAndSettings.startObject("settings");
                mappingsAndSettings.field("number_of_shards", 1);
                mappingsAndSettings.field("number_of_replicas", 1);
                mappingsAndSettings.endObject();
            }
            mappingsAndSettings.endObject();
            Request createIndex = new Request("PUT", "/" + index);
            createIndex.setJsonEntity(Strings.toString(mappingsAndSettings));
            client().performRequest(createIndex);
        } else {
            ensureGreenLongWait(index);

            Request statsRequest = new Request("GET", index + "/_stats");
            statsRequest.addParameter("level", "shards");
            Response response = client().performRequest(statsRequest);
            List<Object> shardStats = ObjectPath.createFromResponse(response).evaluate("indices." + index + ".shards.0");
            assertThat(shardStats, notNullValue());
            assertThat("Expected stats for 2 shards", shardStats, hasSize(2));
            String globalHistoryUUID = null;
            for (Object shard : shardStats) {
                final String nodeId = ObjectPath.evaluate(shard, "routing.node");
                final Boolean primary = ObjectPath.evaluate(shard, "routing.primary");
                logger.info("evaluating: {} , {}", ObjectPath.evaluate(shard, "routing"), ObjectPath.evaluate(shard, "commit"));
                String historyUUID = ObjectPath.evaluate(shard, "commit.user_data.history_uuid");
                assertThat("no history uuid found on " + nodeId + " (primary: " + primary + ")", historyUUID, notNullValue());
                if (globalHistoryUUID == null) {
                    globalHistoryUUID = historyUUID;
                } else {
                    assertThat(
                        "history uuid mismatch on " + nodeId + " (primary: " + primary + ")",
                        historyUUID,
                        equalTo(globalHistoryUUID)
                    );
                }
            }
        }
    }

    public void testSoftDeletes() throws Exception {
        if (isRunningAgainstOldCluster()) {
            XContentBuilder mappingsAndSettings = jsonBuilder();
            mappingsAndSettings.startObject();
            {
                mappingsAndSettings.startObject("settings");
                mappingsAndSettings.field("number_of_shards", 1);
                mappingsAndSettings.field("number_of_replicas", 1);
                if (randomBoolean()) {
                    mappingsAndSettings.field("soft_deletes.enabled", true);
                }
                mappingsAndSettings.endObject();
            }
            mappingsAndSettings.endObject();
            Request createIndex = new Request("PUT", "/" + index);
            createIndex.setJsonEntity(Strings.toString(mappingsAndSettings));
            client().performRequest(createIndex);
            int numDocs = between(10, 100);
            for (int i = 0; i < numDocs; i++) {
                String doc = Strings.toString(JsonXContent.contentBuilder().startObject().field("field", "v1").endObject());
                Request request = new Request("POST", "/" + index + "/_doc/" + i);
                request.setJsonEntity(doc);
                client().performRequest(request);
                refreshAllIndices();
            }
            client().performRequest(new Request("POST", "/" + index + "/_flush"));
            int liveDocs = numDocs;
            assertTotalHits(liveDocs, entityAsMap(client().performRequest(new Request("GET", "/" + index + "/_search"))));
            for (int i = 0; i < numDocs; i++) {
                if (randomBoolean()) {
                    String doc = Strings.toString(JsonXContent.contentBuilder().startObject().field("field", "v2").endObject());
                    Request request = new Request("POST", "/" + index + "/_doc/" + i);
                    request.setJsonEntity(doc);
                    client().performRequest(request);
                } else if (randomBoolean()) {
                    client().performRequest(new Request("DELETE", "/" + index + "/_doc/" + i));
                    liveDocs--;
                }
            }
            refreshAllIndices();
            assertTotalHits(liveDocs, entityAsMap(client().performRequest(new Request("GET", "/" + index + "/_search"))));
            saveInfoDocument(index + "_doc_count", Integer.toString(liveDocs));
        } else {
            int liveDocs = Integer.parseInt(loadInfoDocument(index + "_doc_count"));
            assertTotalHits(liveDocs, entityAsMap(client().performRequest(new Request("GET", "/" + index + "/_search"))));
        }
    }

    /**
     * This test creates an index in the old cluster and then closes it. When the cluster is fully restarted in a newer version,
     * it verifies that the index exists and is replicated if the old version supports replication.
     */
    public void testClosedIndices() throws Exception {
        if (isRunningAgainstOldCluster()) {
            createIndex(index, Settings.builder().put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1).build());
            ensureGreen(index);

            int numDocs = 0;
            if (randomBoolean()) {
                numDocs = between(1, 100);
                for (int i = 0; i < numDocs; i++) {
                    final Request request = new Request("POST", "/" + index + "/_doc/" + i);
                    request.setJsonEntity(Strings.toString(JsonXContent.contentBuilder().startObject().field("field", "v1").endObject()));
                    assertOK(client().performRequest(request));
                    if (rarely()) {
                        refreshAllIndices();
                    }
                }
                refreshAllIndices();
            }

            assertTotalHits(numDocs, entityAsMap(client().performRequest(new Request("GET", "/" + index + "/_search"))));
            saveInfoDocument(index + "_doc_count", Integer.toString(numDocs));
            closeIndex(index);
        }

        if (getOldClusterVersion().onOrAfter(Version.V_7_2_0)) {
            ensureGreenLongWait(index);
            assertClosedIndex(index, true);
        } else {
            assertClosedIndex(index, false);
        }

        if (isRunningAgainstOldCluster() == false) {
            openIndex(index);
            ensureGreen(index);

            final int expectedNumDocs = Integer.parseInt(loadInfoDocument(index + "_doc_count"));
            assertTotalHits(expectedNumDocs, entityAsMap(client().performRequest(new Request("GET", "/" + index + "/_search"))));
        }
    }

    /**
     * Asserts that an index is closed in the cluster state. If `checkRoutingTable` is true, it also asserts
     * that the index has started shards.
     */
    @SuppressWarnings("unchecked")
    private void assertClosedIndex(final String indexName, final boolean checkRoutingTable) throws IOException {
        final Map<String, ?> state = entityAsMap(client().performRequest(new Request("GET", "/_cluster/state")));

        final Map<String, ?> metadata = (Map<String, Object>) XContentMapValues.extractValue("metadata.indices." + indexName, state);
        assertThat(metadata, notNullValue());
        assertThat(metadata.get("state"), equalTo("close"));

        final Map<String, ?> blocks = (Map<String, Object>) XContentMapValues.extractValue("blocks.indices." + indexName, state);
        assertThat(blocks, notNullValue());
        assertThat(blocks.containsKey(String.valueOf(MetadataIndexStateService.INDEX_CLOSED_BLOCK_ID)), is(true));

        final Map<String, ?> settings = (Map<String, Object>) XContentMapValues.extractValue("settings", metadata);
        assertThat(settings, notNullValue());

        final Map<String, ?> routingTable = (Map<String, Object>) XContentMapValues.extractValue(
            "routing_table.indices." + indexName,
            state
        );
        if (checkRoutingTable) {
            assertThat(routingTable, notNullValue());
            assertThat(Booleans.parseBoolean((String) XContentMapValues.extractValue("index.verified_before_close", settings)), is(true));
            final String numberOfShards = (String) XContentMapValues.extractValue("index.number_of_shards", settings);
            assertThat(numberOfShards, notNullValue());
            final int nbShards = Integer.parseInt(numberOfShards);
            assertThat(nbShards, greaterThanOrEqualTo(1));

            for (int i = 0; i < nbShards; i++) {
                final Collection<Map<String, ?>> shards = (Collection<Map<String, ?>>) XContentMapValues.extractValue(
                    "shards." + i,
                    routingTable
                );
                assertThat(shards, notNullValue());
                assertThat(shards.size(), equalTo(2));
                for (Map<String, ?> shard : shards) {
                    assertThat(XContentMapValues.extractValue("shard", shard), equalTo(i));
                    assertThat(XContentMapValues.extractValue("state", shard), equalTo("STARTED"));
                    assertThat(XContentMapValues.extractValue("index", shard), equalTo(indexName));
                }
            }
        } else {
            assertThat(routingTable, nullValue());
            assertThat(XContentMapValues.extractValue("index.verified_before_close", settings), nullValue());
        }
    }

    @SuppressWarnings("unchecked")
    private void checkSnapshot(final String snapshotName, final int count, final Version tookOnVersion) throws IOException {
        // Check the snapshot metadata, especially the version
        Request listSnapshotRequest = new Request("GET", "/_snapshot/repo/" + snapshotName);
        Map<String, Object> snapResponse = entityAsMap(client().performRequest(listSnapshotRequest));

        assertEquals(singletonList(snapshotName), XContentMapValues.extractValue("snapshots.snapshot", snapResponse));
        assertEquals(singletonList("SUCCESS"), XContentMapValues.extractValue("snapshots.state", snapResponse));
        assertEquals(singletonList(tookOnVersion.toString()), XContentMapValues.extractValue("snapshots.version", snapResponse));

        // Remove the routing setting and template so we can test restoring them.
        Request clearRoutingFromSettings = new Request("PUT", "/_cluster/settings");
        clearRoutingFromSettings.setJsonEntity("{\"persistent\":{\"cluster.routing.allocation.exclude.test_attr\": null}}");
        client().performRequest(clearRoutingFromSettings);
        client().performRequest(new Request("DELETE", "/_template/test_template"));

        // Restore
        XContentBuilder restoreCommand = JsonXContent.contentBuilder().startObject();
        restoreCommand.field("include_global_state", true);
        restoreCommand.field("indices", index);
        restoreCommand.field("rename_pattern", index);
        restoreCommand.field("rename_replacement", "restored_" + index);
        restoreCommand.endObject();
        Request restoreRequest = new Request("POST", "/_snapshot/repo/" + snapshotName + "/_restore");
        restoreRequest.addParameter("wait_for_completion", "true");
        restoreRequest.setJsonEntity(Strings.toString(restoreCommand));
        client().performRequest(restoreRequest);

        // Make sure search finds all documents
        Request countRequest = new Request("GET", "/restored_" + index + "/_search");
        countRequest.addParameter("size", "0");
        Map<String, Object> countResponse = entityAsMap(client().performRequest(countRequest));
        assertTotalHits(count, countResponse);

        // Add some extra documents to the index to be sure we can still write to it after restoring it
        int extras = between(1, 100);
        StringBuilder bulk = new StringBuilder();
        for (int i = 0; i < extras; i++) {
            bulk.append("{\"index\":{\"_id\":\"").append(count + i).append("\"}}\n");
            bulk.append("{\"test\":\"test\"}\n");
        }

        Request writeToRestoredRequest = new Request("POST", "/restored_" + index + "/_bulk");

        writeToRestoredRequest.addParameter("refresh", "true");
        writeToRestoredRequest.setJsonEntity(bulk.toString());
        assertThat(EntityUtils.toString(client().performRequest(writeToRestoredRequest).getEntity()), containsString("\"errors\":false"));

        // And count to make sure the add worked
        // Make sure search finds all documents
        Request countAfterWriteRequest = new Request("GET", "/restored_" + index + "/_search");
        countAfterWriteRequest.addParameter("size", "0");
        Map<String, Object> countAfterResponse = entityAsMap(client().performRequest(countRequest));
        assertTotalHits(count + extras, countAfterResponse);

        // Clean up the index for the next iteration
        client().performRequest(new Request("DELETE", "/restored_*"));

        // Check settings added by the restore process
        Request clusterSettingsRequest = new Request("GET", "/_cluster/settings");
        clusterSettingsRequest.addParameter("flat_settings", "true");
        Map<String, Object> clusterSettingsResponse = entityAsMap(client().performRequest(clusterSettingsRequest));
        @SuppressWarnings("unchecked")
        final Map<String, Object> persistentSettings = (Map<String, Object>) clusterSettingsResponse.get("persistent");
        assertThat(persistentSettings.get("cluster.routing.allocation.exclude.test_attr"), equalTo(getOldClusterVersion().toString()));

        // Check that the template was restored successfully
        Request getTemplateRequest = new Request("GET", "/_template/test_template");

        Map<String, Object> getTemplateResponse = entityAsMap(client().performRequest(getTemplateRequest));
        Map<String, Object> expectedTemplate = new HashMap<>();
        expectedTemplate.put("index_patterns", singletonList("evil_*"));

        expectedTemplate.put("settings", singletonMap("index", singletonMap("number_of_shards", "1")));
        expectedTemplate.put("mappings", singletonMap("_source", singletonMap("enabled", true)));

        expectedTemplate.put("order", 0);
        Map<String, Object> aliases = new HashMap<>();
        aliases.put("alias1", emptyMap());
        aliases.put("alias2", singletonMap("filter", singletonMap("term", singletonMap("version", tookOnVersion.toString()))));
        expectedTemplate.put("aliases", aliases);
        expectedTemplate = singletonMap("test_template", expectedTemplate);
        if (false == expectedTemplate.equals(getTemplateResponse)) {
            NotEqualMessageBuilder builder = new NotEqualMessageBuilder();
            builder.compareMaps(getTemplateResponse, expectedTemplate);
            logger.info("expected: {}\nactual:{}", expectedTemplate, getTemplateResponse);
            fail("template doesn't match:\n" + builder.toString());
        }
    }

    private void indexRandomDocuments(
        final int count,
        final boolean flushAllowed,
        final boolean saveInfo,
        final boolean specifyId,
        final CheckedFunction<Integer, XContentBuilder, IOException> docSupplier
    ) throws IOException {
        logger.info("Indexing {} random documents", count);
        for (int i = 0; i < count; i++) {
            logger.debug("Indexing document [{}]", i);
            Request createDocument = new Request("POST", "/" + index + "/_doc/" + (specifyId ? i : ""));
            createDocument.setJsonEntity(Strings.toString(docSupplier.apply(i)));
            client().performRequest(createDocument);
            if (rarely()) {
                refreshAllIndices();
            }
            if (flushAllowed && rarely()) {
                logger.debug("Flushing [{}]", index);
                client().performRequest(new Request("POST", "/" + index + "/_flush"));
            }
        }
        if (saveInfo) {
            saveInfoDocument(index + "_count", Integer.toString(count));
        }
    }

    private void indexDocument(String id) throws IOException {
        final Request indexRequest = new Request("POST", "/" + index + "/" + "_doc/" + id);
        indexRequest.setJsonEntity(Strings.toString(JsonXContent.contentBuilder().startObject().field("f", "v").endObject()));
        assertOK(client().performRequest(indexRequest));
    }

    private int countOfIndexedRandomDocuments() throws IOException {
        return Integer.parseInt(loadInfoDocument(index + "_count"));
    }

    private void saveInfoDocument(String id, String value) throws IOException {
        XContentBuilder infoDoc = JsonXContent.contentBuilder().startObject();
        infoDoc.field("value", value);
        infoDoc.endObject();
        // Only create the first version so we know how many documents are created when the index is first created
        Request request = new Request("PUT", "/info/_doc/" + id);
        request.addParameter("op_type", "create");
        request.setJsonEntity(Strings.toString(infoDoc));
        client().performRequest(request);
    }

    private String loadInfoDocument(String id) throws IOException {
        Request request = new Request("GET", "/info/_doc/" + id);
        request.addParameter("filter_path", "_source");
        String doc = toStr(client().performRequest(request));
        Matcher m = Pattern.compile("\"value\":\"(.+)\"").matcher(doc);
        assertTrue(doc, m.find());
        return m.group(1);
    }

    private List<String> dataNodes(String indexName, RestClient client) throws IOException {
        Request request = new Request("GET", indexName + "/_stats");
        request.addParameter("level", "shards");
        Response response = client.performRequest(request);
        List<String> nodes = new ArrayList<>();
        List<Object> shardStats = ObjectPath.createFromResponse(response).evaluate("indices." + indexName + ".shards.0");
        for (Object shard : shardStats) {
            final String nodeId = ObjectPath.evaluate(shard, "routing.node");
            nodes.add(nodeId);
        }
        return nodes;
    }

    /**
     * Wait for an index to have green health, waiting longer than
     * {@link ESRestTestCase#ensureGreen}.
     */
    protected void ensureGreenLongWait(String indexName) throws IOException {
        Request request = new Request("GET", "/_cluster/health/" + indexName);
        request.addParameter("timeout", "2m");
        request.addParameter("wait_for_status", "green");
        request.addParameter("wait_for_no_relocating_shards", "true");
        request.addParameter("wait_for_events", "languid");
        request.addParameter("level", "shards");
        Map<String, Object> healthRsp = entityAsMap(client().performRequest(request));
        logger.info("health api response: {}", healthRsp);
        assertEquals("green", healthRsp.get("status"));
        assertFalse((Boolean) healthRsp.get("timed_out"));
    }

    public void testPeerRecoveryRetentionLeases() throws Exception {
        if (isRunningAgainstOldCluster()) {
            XContentBuilder settings = jsonBuilder();
            settings.startObject();
            {
                settings.startObject("settings");
                settings.field("number_of_shards", between(1, 5));
                settings.field("number_of_replicas", between(0, 1));
                settings.endObject();
            }
            settings.endObject();

            Request createIndex = new Request("PUT", "/" + index);
            createIndex.setJsonEntity(Strings.toString(settings));
            client().performRequest(createIndex);
        }
        ensureGreen(index);
        ensurePeerRecoveryRetentionLeasesRenewedAndSynced(index);
    }

    /**
     * Tests that with or without soft-deletes, we should perform an operation-based recovery if there were some
     * but not too many uncommitted documents (i.e., less than 10% of committed documents or the extra translog)
     * before we restart the cluster. This is important when we move from translog based to retention leases based
     * peer recoveries.
     */
    public void testOperationBasedRecovery() throws Exception {
        if (isRunningAgainstOldCluster()) {
            Settings.Builder settings = Settings.builder()
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1);
            if (minimumNodeVersion().before(Version.V_8_0_0) && randomBoolean()) {
                settings.put(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), randomBoolean());
            }
            final String mappings = randomBoolean() ? "\"_source\": { \"enabled\": false}" : null;
            createIndex(index, settings.build(), mappings);
            ensureGreen(index);
            int committedDocs = randomIntBetween(100, 200);
            for (int i = 0; i < committedDocs; i++) {
                indexDocument(Integer.toString(i));
                if (rarely()) {
                    flush(index, randomBoolean());
                }
            }
            flush(index, true);
            ensurePeerRecoveryRetentionLeasesRenewedAndSynced(index);
            // less than 10% of the committed docs (see IndexSetting#FILE_BASED_RECOVERY_THRESHOLD_SETTING).
            int uncommittedDocs = randomIntBetween(0, (int) (committedDocs * 0.1));
            for (int i = 0; i < uncommittedDocs; i++) {
                final String id = Integer.toString(randomIntBetween(1, 100));
                indexDocument(id);
            }
        } else {
            ensureGreen(index);
            assertNoFileBasedRecovery(index, n -> true);
            ensurePeerRecoveryRetentionLeasesRenewedAndSynced(index);
        }
    }

    /**
     * Verifies that once all shard copies on the new version, we should turn off the translog retention for indices with soft-deletes.
     */
    public void testTurnOffTranslogRetentionAfterUpgraded() throws Exception {
        if (isRunningAgainstOldCluster()) {
            createIndex(
                index,
                Settings.builder()
                    .put(IndexMetadata.INDEX_NUMBER_OF_SHARDS_SETTING.getKey(), 1)
                    .put(IndexMetadata.INDEX_NUMBER_OF_REPLICAS_SETTING.getKey(), 1)
                    .put(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), true)
                    .build()
            );
            ensureGreen(index);
            int numDocs = randomIntBetween(10, 100);
            for (int i = 0; i < numDocs; i++) {
                indexDocument(Integer.toString(randomIntBetween(1, 100)));
                if (rarely()) {
                    flush(index, randomBoolean());
                }
            }
        } else {
            ensureGreen(index);
            flush(index, true);
            assertEmptyTranslog(index);
            ensurePeerRecoveryRetentionLeasesRenewedAndSynced(index);
        }
    }

    public void testResize() throws Exception {
        int numDocs;
        if (isRunningAgainstOldCluster()) {
            final Settings.Builder settings = Settings.builder()
                .put(IndexMetadata.INDEX_NUMBER_OF_SHARDS_SETTING.getKey(), 3)
                .put(IndexMetadata.INDEX_NUMBER_OF_REPLICAS_SETTING.getKey(), 1);
            if (minimumNodeVersion().before(Version.V_8_0_0) && randomBoolean()) {
                settings.put(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), false);
            }
            final String mappings = randomBoolean() ? "\"_source\": { \"enabled\": false}" : null;
            createIndex(index, settings.build(), mappings);
            numDocs = randomIntBetween(10, 1000);
            for (int i = 0; i < numDocs; i++) {
                indexDocument(Integer.toString(i));
                if (rarely()) {
                    flush(index, randomBoolean());
                }
            }
            saveInfoDocument("num_doc_" + index, Integer.toString(numDocs));
            ensureGreen(index);
        } else {
            ensureGreen(index);
            numDocs = Integer.parseInt(loadInfoDocument("num_doc_" + index));
            int moreDocs = randomIntBetween(0, 100);
            for (int i = 0; i < moreDocs; i++) {
                indexDocument(Integer.toString(numDocs + i));
                if (rarely()) {
                    flush(index, randomBoolean());
                }
            }
            Request updateSettingsRequest = new Request("PUT", "/" + index + "/_settings");
            updateSettingsRequest.setJsonEntity("{\"settings\": {\"index.blocks.write\": true}}");
            client().performRequest(updateSettingsRequest);
            {
                final String target = index + "_shrunken";
                Request shrinkRequest = new Request("PUT", "/" + index + "/_shrink/" + target);
                Settings.Builder settings = Settings.builder().put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1);
                if (randomBoolean()) {
                    settings.put(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), true);
                }
                shrinkRequest.setJsonEntity("{\"settings\":" + Strings.toString(settings.build()) + "}");
                client().performRequest(shrinkRequest);
                ensureGreenLongWait(target);
                assertNumHits(target, numDocs + moreDocs, 1);
            }
            {
                final String target = index + "_split";
                Settings.Builder settings = Settings.builder().put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 6);
                if (randomBoolean()) {
                    settings.put(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), true);
                }
                Request splitRequest = new Request("PUT", "/" + index + "/_split/" + target);
                splitRequest.setJsonEntity("{\"settings\":" + Strings.toString(settings.build()) + "}");
                client().performRequest(splitRequest);
                ensureGreenLongWait(target);
                assertNumHits(target, numDocs + moreDocs, 6);
            }
            {
                final String target = index + "_cloned";
                client().performRequest(new Request("PUT", "/" + index + "/_clone/" + target));
                ensureGreenLongWait(target);
                assertNumHits(target, numDocs + moreDocs, 3);
            }
        }
    }

    @SuppressWarnings("unchecked")
    @AwaitsFix(bugUrl = "https://github.com/elastic/elasticsearch/issues/81409")
    public void testSystemIndexMetadataIsUpgraded() throws Exception {
        final String systemIndexWarning = "this request accesses system indices: [.tasks], but in a future major version, direct "
            + "access to system indices will be prevented by default";
        if (isRunningAgainstOldCluster()) {
            // create index
            Request createTestIndex = new Request("PUT", "/test_index_old");
            createTestIndex.setJsonEntity("{\"settings\": {\"index.number_of_replicas\": 0}}");
            client().performRequest(createTestIndex);

            Request bulk = new Request("POST", "/_bulk");
            bulk.addParameter("refresh", "true");
            bulk.setJsonEntity("{\"index\": {\"_index\": \"test_index_old\"}}\n" + "{\"f1\": \"v1\", \"f2\": \"v2\"}\n");
            client().performRequest(bulk);

            // start a async reindex job
            Request reindex = new Request("POST", "/_reindex");
            reindex.setJsonEntity(
                "{\n"
                    + "  \"source\":{\n"
                    + "    \"index\":\"test_index_old\"\n"
                    + "  },\n"
                    + "  \"dest\":{\n"
                    + "    \"index\":\"test_index_reindex\"\n"
                    + "  }\n"
                    + "}"
            );
            reindex.addParameter("wait_for_completion", "false");
            Map<String, Object> response = entityAsMap(client().performRequest(reindex));
            String taskId = (String) response.get("task");

            // wait for task
            Request getTask = new Request("GET", "/_tasks/" + taskId);
            getTask.addParameter("wait_for_completion", "true");
            client().performRequest(getTask);

            // make sure .tasks index exists
            Request getTasksIndex = new Request("GET", "/.tasks");
            getTasksIndex.setOptions(expectVersionSpecificWarnings(v -> {
                v.current(systemIndexWarning);
                v.compatible(systemIndexWarning);
            }));
            getTasksIndex.addParameter("allow_no_indices", "false");

            getTasksIndex.setOptions(expectVersionSpecificWarnings(v -> {
                v.current(systemIndexWarning);
                v.compatible(systemIndexWarning);
            }));
            assertBusy(() -> {
                try {
                    assertThat(client().performRequest(getTasksIndex).getStatusLine().getStatusCode(), is(200));
                } catch (ResponseException e) {
                    throw new AssertionError(".tasks index does not exist yet");
                }
            });

            // If we are on 7.x create an alias that includes both a system index and a non-system index so we can be sure it gets
            // upgraded properly. If we're already on 8.x, skip this part of the test.
            if (minimumNodeVersion().before(SYSTEM_INDEX_ENFORCEMENT_VERSION)) {
                // Create an alias to make sure it gets upgraded properly
                Request putAliasRequest = new Request("POST", "/_aliases");
                putAliasRequest.setJsonEntity(
                    "{\n"
                        + "  \"actions\": [\n"
                        + "    {\"add\":  {\"index\":  \".tasks\", \"alias\": \"test-system-alias\"}},\n"
                        + "    {\"add\":  {\"index\":  \"test_index_reindex\", \"alias\": \"test-system-alias\"}}\n"
                        + "  ]\n"
                        + "}"
                );
                putAliasRequest.setOptions(expectVersionSpecificWarnings(v -> {
                    v.current(systemIndexWarning);
                    v.compatible(systemIndexWarning);
                }));
                assertThat(client().performRequest(putAliasRequest).getStatusLine().getStatusCode(), is(200));
            }
        } else {
            assertBusy(() -> {
                Request clusterStateRequest = new Request("GET", "/_cluster/state/metadata");
                Map<String, Object> indices = new XContentTestUtils.JsonMapView(entityAsMap(client().performRequest(clusterStateRequest)))
                    .get("metadata.indices");

                // Make sure our non-system index is still non-system
                assertThat(new XContentTestUtils.JsonMapView(indices).get("test_index_old.system"), is(false));

                // Can't get the .tasks index via JsonMapView because it splits on `.`
                assertThat(indices, hasKey(".tasks"));
                XContentTestUtils.JsonMapView tasksIndex = new XContentTestUtils.JsonMapView((Map<String, Object>) indices.get(".tasks"));
                assertThat(tasksIndex.get("system"), is(true));

                // If .tasks was created in a 7.x version, it should have an alias on it that we need to make sure got upgraded properly.
                final String tasksCreatedVersionString = tasksIndex.get("settings.index.version.created");
                assertThat(tasksCreatedVersionString, notNullValue());
                final Version tasksCreatedVersion = Version.fromId(Integer.parseInt(tasksCreatedVersionString));
                if (tasksCreatedVersion.before(SYSTEM_INDEX_ENFORCEMENT_VERSION)) {
                    // Verify that the alias survived the upgrade
                    Request getAliasRequest = new Request("GET", "/_alias/test-system-alias");
                    getAliasRequest.setOptions(expectVersionSpecificWarnings(v -> {
                        v.current(systemIndexWarning);
                        v.compatible(systemIndexWarning);
                    }));
                    Map<String, Object> aliasResponse = entityAsMap(client().performRequest(getAliasRequest));
                    assertThat(aliasResponse, hasKey(".tasks"));
                    assertThat(aliasResponse, hasKey("test_index_reindex"));
                }
            });
        }
    }

    public void testEnableSoftDeletesOnRestore() throws Exception {
        assumeTrue("soft deletes must be enabled on 8.0+", getOldClusterVersion().before(Version.V_8_0_0));
        final String snapshot = "snapshot-" + index;
        if (isRunningAgainstOldCluster()) {
            final Settings.Builder settings = Settings.builder()
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1);
            settings.put(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), false);
            createIndex(index, settings.build());
            ensureGreen(index);
            int numDocs = randomIntBetween(0, 100);
            indexRandomDocuments(
                numDocs,
                true,
                true,
                randomBoolean(),
                i -> jsonBuilder().startObject().field("field", "value").endObject()
            );
            // create repo
            XContentBuilder repoConfig = JsonXContent.contentBuilder().startObject();
            {
                repoConfig.field("type", "fs");
                repoConfig.startObject("settings");
                {
                    repoConfig.field("compress", randomBoolean());
                    repoConfig.field("location", System.getProperty("tests.path.repo"));
                }
                repoConfig.endObject();
            }
            repoConfig.endObject();
            Request createRepoRequest = new Request("PUT", "/_snapshot/repo");
            createRepoRequest.setJsonEntity(Strings.toString(repoConfig));
            client().performRequest(createRepoRequest);
            // create snapshot
            Request createSnapshot = new Request("PUT", "/_snapshot/repo/" + snapshot);
            createSnapshot.addParameter("wait_for_completion", "true");
            createSnapshot.setJsonEntity("{\"indices\": \"" + index + "\"}");
            client().performRequest(createSnapshot);
        } else {
            String restoredIndex = "restored-" + index;
            // Restore
            XContentBuilder restoreCommand = JsonXContent.contentBuilder().startObject();
            restoreCommand.field("indices", index);
            restoreCommand.field("rename_pattern", index);
            restoreCommand.field("rename_replacement", restoredIndex);
            restoreCommand.startObject("index_settings");
            {
                restoreCommand.field("index.soft_deletes.enabled", true);
            }
            restoreCommand.endObject();
            restoreCommand.endObject();
            Request restoreRequest = new Request("POST", "/_snapshot/repo/" + snapshot + "/_restore");
            restoreRequest.addParameter("wait_for_completion", "true");
            restoreRequest.setJsonEntity(Strings.toString(restoreCommand));
            client().performRequest(restoreRequest);
            ensureGreen(restoredIndex);
            int numDocs = countOfIndexedRandomDocuments();
            assertTotalHits(numDocs, entityAsMap(client().performRequest(new Request("GET", "/" + restoredIndex + "/_search"))));
        }
    }

    public void testForbidDisableSoftDeletesOnRestore() throws Exception {
        final String snapshot = "snapshot-" + index;
        if (isRunningAgainstOldCluster()) {
            final Settings.Builder settings = Settings.builder()
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)
                .put(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), true);
            createIndex(index, settings.build());
            ensureGreen(index);
            int numDocs = randomIntBetween(0, 100);
            indexRandomDocuments(
                numDocs,
                true,
                true,
                randomBoolean(),
                i -> jsonBuilder().startObject().field("field", "value").endObject()
            );
            // create repo
            XContentBuilder repoConfig = JsonXContent.contentBuilder().startObject();
            {
                repoConfig.field("type", "fs");
                repoConfig.startObject("settings");
                {
                    repoConfig.field("compress", randomBoolean());
                    repoConfig.field("location", System.getProperty("tests.path.repo"));
                }
                repoConfig.endObject();
            }
            repoConfig.endObject();
            Request createRepoRequest = new Request("PUT", "/_snapshot/repo");
            createRepoRequest.setJsonEntity(Strings.toString(repoConfig));
            client().performRequest(createRepoRequest);
            // create snapshot
            Request createSnapshot = new Request("PUT", "/_snapshot/repo/" + snapshot);
            createSnapshot.addParameter("wait_for_completion", "true");
            createSnapshot.setJsonEntity("{\"indices\": \"" + index + "\"}");
            client().performRequest(createSnapshot);
        } else {
            // Restore
            XContentBuilder restoreCommand = JsonXContent.contentBuilder().startObject();
            restoreCommand.field("indices", index);
            restoreCommand.field("rename_pattern", index);
            restoreCommand.field("rename_replacement", "restored-" + index);
            restoreCommand.startObject("index_settings");
            {
                restoreCommand.field("index.soft_deletes.enabled", false);
            }
            restoreCommand.endObject();
            restoreCommand.endObject();
            Request restoreRequest = new Request("POST", "/_snapshot/repo/" + snapshot + "/_restore");
            restoreRequest.addParameter("wait_for_completion", "true");
            restoreRequest.setJsonEntity(Strings.toString(restoreCommand));
            final ResponseException error = expectThrows(ResponseException.class, () -> client().performRequest(restoreRequest));
            assertThat(error.getMessage(), containsString("cannot disable setting [index.soft_deletes.enabled] on restore"));
        }
    }

    /**
     * In 7.14 the cluster.remote.*.transport.compress setting was change from a boolean to an enum setting
     * with true/false as options. This test ensures that the old boolean setting in cluster state is
     * translated properly. This test can be removed in 9.0.
     */
    public void testTransportCompressionSetting() throws IOException {
        assumeTrue("the old transport.compress setting existed before 7.14", getOldClusterVersion().before(Version.V_7_14_0));
        assumeTrue(
            "Early versions of 6.x do not have cluster.remote* prefixed settings",
            getOldClusterVersion().onOrAfter(Version.V_7_14_0.minimumCompatibilityVersion())
        );
        if (isRunningAgainstOldCluster()) {
            final Request putSettingsRequest = new Request("PUT", "/_cluster/settings");
            try (XContentBuilder builder = jsonBuilder()) {
                builder.startObject();
                {
                    builder.startObject("persistent");
                    {
                        builder.field("cluster.remote.foo.seeds", Collections.singletonList("localhost:9200"));
                        builder.field("cluster.remote.foo.transport.compress", "true");
                    }
                    builder.endObject();
                }
                builder.endObject();
                putSettingsRequest.setJsonEntity(Strings.toString(builder));
            }
            client().performRequest(putSettingsRequest);
        } else {
            final Request getSettingsRequest = new Request("GET", "/_cluster/settings");
            final Response getSettingsResponse = client().performRequest(getSettingsRequest);
            try (XContentParser parser = createParser(JsonXContent.jsonXContent, getSettingsResponse.getEntity().getContent())) {
                final ClusterGetSettingsResponse clusterGetSettingsResponse = ClusterGetSettingsResponse.fromXContent(parser);
                final Settings settings = clusterGetSettingsResponse.getPersistentSettings();
                assertThat(REMOTE_CLUSTER_COMPRESS.getConcreteSettingForNamespace("foo").get(settings), equalTo(Compression.Enabled.TRUE));
            }
        }
    }

    public static void assertNumHits(String index, int numHits, int totalShards) throws IOException {
        Map<String, Object> resp = entityAsMap(client().performRequest(new Request("GET", "/" + index + "/_search")));
        assertNoFailures(resp);
        assertThat(XContentMapValues.extractValue("_shards.total", resp), equalTo(totalShards));
        assertThat(XContentMapValues.extractValue("_shards.successful", resp), equalTo(totalShards));
        assertThat(extractTotalHits(resp), equalTo(numHits));
    }
}
