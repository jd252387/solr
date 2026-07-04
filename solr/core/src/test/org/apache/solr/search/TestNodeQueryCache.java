/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.search;

import static org.apache.solr.core.CoreContainer.ALLOW_PATHS_SYSPROP;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.lucene.search.LRUQueryCache;
import org.apache.lucene.search.QueryCachingPolicy;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrXmlConfig;
import org.apache.solr.util.EmbeddedSolrServerTestRule;
import org.apache.solr.util.RefCounted;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

/**
 * Tests the node-level Lucene query cache enabled via {@code queryCacheMaxRam} in solr.xml: one
 * {@link LRUQueryCache} shared by all cores, with a per-core {@link QueryCachingPolicy} that
 * survives searcher reopens.
 */
public class TestNodeQueryCache extends SolrTestCaseJ4 {

  @ClassRule
  public static EmbeddedSolrServerTestRule solrTestRule = new EmbeddedSolrServerTestRule();

  @BeforeClass
  public static void setupSolrHome() throws Exception {
    Path home = createTempDir("home");
    Path configSet = createTempDir("configSet");
    System.setProperty(ALLOW_PATHS_SYSPROP, configSet.toAbsolutePath().toString());
    Files.writeString(
        home.resolve("solr.xml"),
        "<solr>\n"
            + "  <str name=\"allowPaths\">${"
            + ALLOW_PATHS_SYSPROP
            + ":}</str>\n"
            + "  <str name=\"queryCacheMaxRam\">1m</str>\n"
            + "</solr>");

    solrTestRule.startSolr(home);

    copyMinConf(configSet);
    solrTestRule.newCollection("core1").withConfigSet(configSet).create();
    solrTestRule.newCollection("core2").withConfigSet(configSet).create();
  }

  @Test
  public void testCacheSharedAcrossCoresWithPerCorePolicy() throws Exception {
    CoreContainer cc = solrTestRule.getCoreContainer();
    LRUQueryCache nodeCache = cc.getNodeQueryCache();
    assertNotNull("cache should be enabled via queryCacheMaxRam", nodeCache);
    try (SolrCore core1 = cc.getCore("core1");
        SolrCore core2 = cc.getCore("core2")) {
      assertNotSame(
          "each core has its own caching policy",
          core1.getQueryCachingPolicy(),
          core2.getQueryCachingPolicy());
      RefCounted<SolrIndexSearcher> s1 = core1.getSearcher();
      RefCounted<SolrIndexSearcher> s2 = core2.getSearcher();
      try {
        assertSame(nodeCache, s1.get().getQueryCache());
        assertSame(nodeCache, s2.get().getQueryCache());
        assertSame(core1.getQueryCachingPolicy(), s1.get().getQueryCachingPolicy());
        assertSame(core2.getQueryCachingPolicy(), s2.get().getQueryCachingPolicy());
      } finally {
        s1.decref();
        s2.decref();
      }
    }
  }

  @Test
  public void testPolicySurvivesSearcherReopen() throws Exception {
    CoreContainer cc = solrTestRule.getCoreContainer();
    SolrClient client = solrTestRule.getSolrClient("core1");
    try (SolrCore core = cc.getCore("core1")) {
      QueryCachingPolicy policy = core.getQueryCachingPolicy();
      SolrIndexSearcher before;
      RefCounted<SolrIndexSearcher> ref = core.getSearcher();
      try {
        before = ref.get();
        assertSame(policy, before.getQueryCachingPolicy());
      } finally {
        ref.decref();
      }

      SolrInputDocument doc = new SolrInputDocument();
      doc.setField("id", "1");
      client.add(doc);
      client.commit();

      ref = core.getSearcher();
      try {
        assertNotSame("commit should have opened a new searcher", before, ref.get());
        assertSame(policy, ref.get().getQueryCachingPolicy());
        assertSame(cc.getNodeQueryCache(), ref.get().getQueryCache());
      } finally {
        ref.decref();
      }
    }
  }

  @Test
  public void testRealtimeSearcherHasCache() throws Exception {
    CoreContainer cc = solrTestRule.getCoreContainer();
    try (SolrCore core = cc.getCore("core2")) {
      RefCounted<SolrIndexSearcher> rt = core.getRealtimeSearcher();
      try {
        assertSame(cc.getNodeQueryCache(), rt.get().getQueryCache());
      } finally {
        rt.decref();
      }
    }
  }

  @Test
  public void testDisabledByDefault() throws Exception {
    CoreContainer cc = new CoreContainer(SolrXmlConfig.fromString(createTempDir(), "<solr/>"));
    try {
      cc.load();
      assertNull(cc.getNodeQueryCache());
    } finally {
      cc.shutdown();
    }
  }
}
