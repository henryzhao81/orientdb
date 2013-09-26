/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orientechnologies.orient.graph.sql;

import java.util.Collection;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.sql.query.OSQLSynchQuery;
import com.tinkerpop.blueprints.impls.orient.OrientGraph;
import com.tinkerpop.blueprints.impls.orient.OrientVertex;

@Test
public class SQLGraphFunctions {
  private OrientGraph graph;
  private String      url;

  public SQLGraphFunctions() {
    this("memory:testgraph");
  }

  public SQLGraphFunctions(String iURL) {
    url = iURL;
    graph = new OrientGraph(iURL);

    OrientVertex v1 = graph.addVertex(null, "name", "A");
    OrientVertex v2 = graph.addVertex(null, "name", "B");
    OrientVertex v3 = graph.addVertex(null, "name", "C");
    OrientVertex v4 = graph.addVertex(null, "name", "D");
    OrientVertex v5 = graph.addVertex(null, "name", "E");
    OrientVertex v6 = graph.addVertex(null, "name", "F");

    v1.addEdge(null, v2, null, null, "weight", 10);
    v2.addEdge(null, v3, null, null, "weight", 20);
    v3.addEdge(null, v4, null, null, "weight", 30);
    v4.addEdge(null, v5, null, null, "weight", 40);
    v5.addEdge(null, v6, null, null, "weight", 50);
    v5.addEdge(null, v1, null, null, "weight", 100);

    graph.commit();
  }

  public void checkDijkstra() {
    String subquery = "select $current, $target, Dijkstra($current, $target , 'weight') as path from V let $target = ( select from V where name = \'C\' ) where 1 > 0";
    Iterable<ODocument> result = graph.command(new OSQLSynchQuery<ODocument>(subquery)).execute();
    Assert.assertTrue(result.iterator().hasNext());

    for (ODocument d : result) {
      System.out.println("Shortest path from " + ((ODocument) d.field("$current")).field("name") + " and "
          + ((Collection<ODocument>) d.field("$target")).iterator().next().field("name") + " is: " + d.field("path"));
    }
  }
}
