/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.dependency.tree;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import org.apache.maven.api.Dependency;
import org.apache.maven.plugins.dependency.testUtils.DependencyStubs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the serialization formats of the <code>tree</code> goal. The tests of the Maven 3 version ran the mojo with a
 * stubbed project; the mojo itself is covered by the <code>tree*</code> integration tests.
 *
 * @author <a href="mailto:markhobson@gmail.com">Mark Hobson</a>
 */
class TreeSerializationTest {

    private DependencyNode root;

    @BeforeEach
    void setUp() {
        Dependency project = DependencyStubs.dependency("testGroupId", "project", "1.0", "compile", "jar", "");
        root = new DependencyNode(null, project, null, "testGroupId:project:jar:1.0:compile");
        root.setChildren(new ArrayList<>());
        root.getChildren().add(child(root, "release", "1.0"));
        root.getChildren().add(child(root, "snapshot", "2.0-SNAPSHOT"));
    }

    private DependencyNode child(DependencyNode parent, String artifactId, String version) {
        Dependency dependency = DependencyStubs.dependency("testGroupId", artifactId, version, "compile", "jar", "");
        DependencyNode node = new DependencyNode(
                parent, dependency, dependency, "testGroupId:" + artifactId + ":jar:" + version + ":compile");
        node.setChildren(new ArrayList<>());
        return node;
    }

    private List<String> serialize(DependencyNodeVisitor visitor, StringWriter writer) {
        root.accept(visitor);
        return writer.toString().lines().collect(Collectors.toList());
    }

    private boolean findString(List<String> contents, String str) {
        return contents.stream().anyMatch(line -> line.contains(str));
    }

    @Test
    void testTreeDotSerializing() {
        StringWriter writer = new StringWriter();
        List<String> contents = serialize(new DOTDependencyNodeVisitor(writer), writer);
        assertTrue(findString(contents, "digraph \"testGroupId:project:jar:1.0:compile\" {"));
        assertTrue(findString(
                contents,
                "\"testGroupId:project:jar:1.0:compile\" -> \"testGroupId:snapshot:jar:2.0-SNAPSHOT:compile\""));
        assertTrue(findString(
                contents, "\"testGroupId:project:jar:1.0:compile\" -> \"testGroupId:release:jar:1.0:compile\""));
    }

    @Test
    void testTreeGraphMLSerializing() {
        StringWriter writer = new StringWriter();
        List<String> contents = serialize(new GraphmlDependencyNodeVisitor(writer), writer);

        assertTrue(findString(contents, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(findString(contents, "<y:NodeLabel>testGroupId:project:jar:1.0:compile</y:NodeLabel>"));
        assertTrue(findString(contents, "<y:NodeLabel>testGroupId:snapshot:jar:2.0-SNAPSHOT:compile</y:NodeLabel>"));
        assertTrue(findString(contents, "<y:NodeLabel>testGroupId:release:jar:1.0:compile</y:NodeLabel>"));
        assertTrue(findString(contents, "<key for=\"node\" id=\"d0\" yfiles.type=\"nodegraphics\"/>"));
        assertTrue(findString(contents, "<key for=\"edge\" id=\"d1\" yfiles.type=\"edgegraphics\"/>"));
        assertTrue(findString(contents, "<y:EdgeLabel>compile</y:EdgeLabel>"));
    }

    @Test
    void testTreeTGFSerializing() {
        StringWriter writer = new StringWriter();
        List<String> contents = serialize(new TGFDependencyNodeVisitor(writer), writer);
        assertTrue(findString(contents, "testGroupId:project:jar:1.0:compile"));
        assertTrue(findString(contents, "testGroupId:snapshot:jar:2.0-SNAPSHOT:compile"));
        assertTrue(findString(contents, "testGroupId:release:jar:1.0:compile"));
    }

    /**
     * Test the JSON format serialization on DependencyNodes with circular dependence
     */
    @Test
    void testTreeJsonCircularDependency() throws IOException {
        Dependency dependency1 = DependencyStubs.dependency("testGroupId", "project1", "1.0", "compile", "jar", "");
        DependencyNode node1 = new DependencyNode(null, dependency1, dependency1, "project1");
        Dependency dependency2 = DependencyStubs.dependency("testGroupId", "project2", "1.0", "compile", "jar", "");
        DependencyNode node2 = new DependencyNode(null, dependency2, dependency2, "project2");

        node1.setChildren(new ArrayList<>());
        node2.setChildren(new ArrayList<>());

        node1.getChildren().add(node2);
        node2.getChildren().add(node1);

        StringWriter writer = new StringWriter();
        new JsonDependencyNodeVisitor(writer).visit(node1);
    }

    /*
     * Test parsing of JSON output and verify all key-value pairs
     */
    @Test
    void testTreeJsonParsing() {
        StringWriter writer = new StringWriter();
        List<String> contents = serialize(new JsonDependencyNodeVisitor(writer), writer);

        System.setProperty("jakarta.json.provider", "org.glassfish.json.JsonProviderImpl");
        try (JsonReader reader = Json.createReader(new StringReader(String.join(System.lineSeparator(), contents)))) {
            JsonObject rootObject = reader.readObject();

            assertEquals("testGroupId", rootObject.getString("groupId"));
            assertEquals("project", rootObject.getString("artifactId"));
            assertEquals("1.0", rootObject.getString("version"));
            assertEquals("jar", rootObject.getString("type"));
            assertEquals("compile", rootObject.getString("scope"));
            assertEquals("", rootObject.getString("classifier"));
            assertEquals("false", rootObject.getString("optional"));

            JsonArray children = rootObject.getJsonArray("children");
            assertEquals(2, children.size());

            List<JsonObject> sortedChildren = children.stream()
                    .map(JsonObject.class::cast)
                    .sorted(Comparator.comparing(child -> child.getString("artifactId")))
                    .collect(Collectors.toList());

            // Now that children are sorted, we can assert their values in a fixed order
            JsonObject child0 = sortedChildren.get(0);
            assertEquals("testGroupId", child0.getString("groupId"));
            assertEquals("release", child0.getString("artifactId"));
            assertEquals("1.0", child0.getString("version"));
            assertEquals("jar", child0.getString("type"));
            assertEquals("compile", child0.getString("scope"));
            assertEquals("", child0.getString("classifier"));
            assertEquals("false", child0.getString("optional"));

            JsonObject child1 = sortedChildren.get(1);
            assertEquals("testGroupId", child1.getString("groupId"));
            assertEquals("snapshot", child1.getString("artifactId"));
            assertEquals("2.0-SNAPSHOT", child1.getString("version"));
            assertEquals("jar", child1.getString("type"));
            assertEquals("compile", child1.getString("scope"));
            assertEquals("", child1.getString("classifier"));
            assertEquals("false", child1.getString("optional"));
        }
    }
}
