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
package org.apache.solr.highlight;

import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.request.SolrQueryRequest;
import org.junit.BeforeClass;

/** Tests hl.matchedQueries: named-query (name= local param) attribution in highlight pre-tags. */
public class TestUnifiedSolrHighlighterMatchedQueries extends SolrTestCaseJ4 {

  @BeforeClass
  public static void beforeClass() throws Exception {
    System.setProperty("filterCache.enabled", "false");
    System.setProperty("queryResultCache.enabled", "false");
    System.setProperty("documentCache.enabled", "true");
    initCore("solrconfig-cache-enable-disable.xml", "schema-unifiedhighlight.xml");
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    clearIndex();
    assertU(
        adoc(
            "id", "101",
            "text", "second document",
            "text_stemmed", "Walked in The Park"));
    assertU(commit());
  }

  public static SolrQueryRequest req(String... params) {
    return SolrTestCaseJ4.req(params, "hl.method", "unified", "hl", "true");
  }

  /** Asserts the XML response contains the snippet, escaped the way Solr writes char data. */
  private static void assertResponseContainsSnippet(String response, String snippet) {
    String escaped = snippet.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    assertTrue(
        "expected snippet:\n" + snippet + "\nin response:\n" + response,
        response.contains(escaped));
  }

  public void testPhraseWithStemming() throws Exception {
    String response =
        h.query(
            req(
                "q", "{!field f=text_stemmed name=id_1 v='Walking in The Park'}",
                "hl.fl", "text_stemmed",
                "hl.matchedQueries", "true"));
    assertResponseContainsSnippet(
        response,
        "<em data-matched-queries='[{\"name\":\"id_1\","
            + "\"original\":\"Walking in The Park\","
            + "\"analyzed\":\"walk in the park\"}]'>Walked in The Park</em>");
  }

  public void testNamedAndUnnamedSubQueries() throws Exception {
    String response =
        h.query(
            req(
                "q",
                "{!bool must='{!field f=text name=one v=document}'"
                    + " should='{!field f=text v=second}'}",
                "hl.fl",
                "text",
                "hl.matchedQueries",
                "true"));
    // named sub-query gets attributed
    assertResponseContainsSnippet(
        response,
        "<em data-matched-queries='[{\"name\":\"one\",\"original\":\"document\","
            + "\"analyzed\":\"document\"}]'>document</em>");
    // unnamed sub-query keeps the plain pre-tag
    assertResponseContainsSnippet(response, "<em>second</em>");
  }

  public void testTermSharedByTwoNamedQueries() throws Exception {
    String response =
        h.query(
            req(
                "q",
                "{!bool must='{!field f=text name=one v=document}'"
                    + " should='{!field f=text name=two v=document}'}",
                "hl.fl",
                "text",
                "hl.matchedQueries",
                "true"));
    // both names are attributed to the one highlight; their order follows parse order,
    // an implementation detail of the query parser, so assert each entry separately
    assertResponseContainsSnippet(
        response, "{\"name\":\"one\",\"original\":\"document\",\"analyzed\":\"document\"}");
    assertResponseContainsSnippet(
        response, "{\"name\":\"two\",\"original\":\"document\",\"analyzed\":\"document\"}");
    assertResponseContainsSnippet(response, "\"}]'>document</em>");
  }

  public void testOffByDefault() {
    assertQ(
        "named query without hl.matchedQueries leaves tags plain",
        req("q", "{!field f=text name=id_1 v=document}", "hl.fl", "text"),
        "//lst[@name='highlighting']/lst[@name='101']/arr[@name='text']/str='second <em>document</em>'");
  }

  public void testWeightMatchesDisabled() throws Exception {
    String response =
        h.query(
            req(
                "q", "{!field f=text_stemmed name=id_1 v='Walking in The Park'}",
                "hl.fl", "text_stemmed",
                "hl.matchedQueries", "true",
                "hl.weightMatches", "false"));
    // each phrase term is highlighted separately; analyzed is the single post-analysis term
    assertResponseContainsSnippet(
        response,
        "<em data-matched-queries='[{\"name\":\"id_1\","
            + "\"original\":\"Walking in The Park\",\"analyzed\":\"walk\"}]'>Walked</em>");
    assertResponseContainsSnippet(
        response,
        "<em data-matched-queries='[{\"name\":\"id_1\","
            + "\"original\":\"Walking in The Park\",\"analyzed\":\"park\"}]'>Park</em>");
  }
}
