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

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import org.apache.maven.api.Dependency;
import org.apache.maven.api.DependencyScope;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.Project;
import org.apache.maven.api.RemoteRepository;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.DependencyResolverException;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.plugins.dependency.tree.SerializingDependencyNodeVisitor.GraphTokens;
import org.apache.maven.plugins.dependency.utils.DependencyUtil;
import org.apache.maven.plugins.dependency.utils.ResolvedDependency;
import org.apache.maven.shared.artifact.filter.ScopeArtifactFilter;
import org.apache.maven.shared.artifact.filter.StrictPatternExcludesArtifactFilter;
import org.apache.maven.shared.artifact.filter.StrictPatternIncludesArtifactFilter;

/**
 * Displays the dependency tree for this project. Multiple formats are supported: text (by default), but also
 * <a href="https://en.wikipedia.org/wiki/DOT_language">DOT</a>,
 * <a href="https://en.wikipedia.org/wiki/GraphML">GraphML</a>,
 * <a href="https://en.wikipedia.org/wiki/Trivial_Graph_Format">TGF</a>,
 * <a href="https://en.wikipedia.org/wiki/JSON">JSON</a> and
 * <a href="https://mermaid.js.org/syntax/flowchart.html">Mermaid</a>.
 *
 * @author <a href="mailto:markhobson@gmail.com">Mark Hobson</a>
 * @since 2.0-alpha-5
 */
@Mojo(name = "tree")
public class TreeMojo implements org.apache.maven.api.plugin.Mojo {
    // fields -----------------------------------------------------------------

    /**
     * The Maven project.
     */
    @Inject
    private Project project;

    @Inject
    private Session session;

    @Inject
    private Log log;

    private final DependencyTreeBuilder dependencyTreeBuilder = new DependencyTreeBuilder();

    @Parameter(property = "outputEncoding", defaultValue = "${project.reporting.outputEncoding}")
    private String outputEncoding;

    /**
     * If specified, this parameter will cause the dependency tree to be written to the path specified, instead of
     * writing to the console.
     *
     * @since 2.0-alpha-5
     */
    @Parameter(property = "outputFile")
    private File outputFile;

    /**
     * If specified, this parameter will cause the dependency tree to be written using the specified format. Currently
     * supported formats are: <code>text</code> (default), <code>dot</code>, <code>graphml</code>, <code>tgf</code>,
     * <code>json</code> (since 3.7.0) and <code>mermaid</code> (since 3.12.0).
     * These additional formats can be plotted to image files.
     *
     * @since 2.2
     */
    @Parameter(property = "outputType", defaultValue = "text")
    private String outputType;

    /**
     * The scope to filter by when resolving the dependency tree, or <code>null</code> to include dependencies from all
     * scopes.
     *
     * @since 2.0-alpha-5
     */
    @Parameter(property = "scope")
    private String scope;

    /**
     * Whether to include omitted nodes in the serialized dependency tree.
     *
     * @since 2.0-alpha-6
     */
    @Parameter(property = "verbose", defaultValue = "false")
    private boolean verbose;

    /**
     * The token set name to use when outputting the dependency tree. Possible values are <code>whitespace</code>,
     * <code>standard</code> or <code>extended</code>, which use whitespace, standard (ie ASCII) or extended character
     * sets respectively.
     *
     * @since 2.0-alpha-6
     */
    @Parameter(property = "tokens", defaultValue = "standard")
    private String tokens;

    /**
     * A comma-separated list of artifacts to filter the serialized dependency tree by, or <code>null</code> not to
     * filter the dependency tree. The filter syntax is:
     *
     * <pre>
     * [groupId]:[artifactId]:[type]:[version]
     * </pre>
     *
     * where each pattern segment is optional and supports full and partial <code>*</code> wildcards. An empty pattern
     * segment is treated as an implicit wildcard.
     * <p>
     * For example, <code>org.apache.*</code> will match all artifacts whose group id starts with
     * <code>org.apache.</code>, and <code>:::*-SNAPSHOT</code> will match all snapshot artifacts.
     * </p>
     * Paths leading to included artifacts are retained. If an artifact is also beneath a subtree matched by
     * {@link #excludes}, the exclusion takes precedence and the artifact is not included.
     *
     * The patterns are matched by {@code StrictPatternIncludesArtifactFilter}.
     *
     * @since 2.0-alpha-6
     */
    @Parameter(property = "includes")
    private List<String> includes;

    /**
     * A comma-separated list of artifacts to filter from the serialized dependency tree, or <code>null</code> not to
     * filter any artifacts from the dependency tree. The filter syntax is:
     *
     * <pre>
     * [groupId]:[artifactId]:[type]:[version]
     * </pre>
     *
     * where each pattern segment is optional and supports full and partial <code>*</code> wildcards. An empty pattern
     * segment is treated as an implicit wildcard.
     * <p>
     * For example, <code>org.apache.*</code> will match all artifacts whose group id starts with
     * <code>org.apache.</code>, and <code>:::*-SNAPSHOT</code> will match all snapshot artifacts.
     * </p>
     * A matching artifact and its entire dependency subtree are removed from the serialized dependency tree.
     * Exclusions are applied before {@link #includes} and take precedence.
     *
     * The patterns are matched by {@code StrictPatternExcludesArtifactFilter}.
     *
     * @since 2.0-alpha-6
     */
    @Parameter(property = "excludes")
    private List<String> excludes;

    /**
     * Whether to show the size of the file of each artifact and, for an artifact that brings in others, its total.
     * Showing sizes downloads the artifacts that are not in the local repository yet, which the goal does not do
     * otherwise.
     * <p>
     * The total of a node is the size of all the distinct artifacts that the dependency brings in as Maven resolves
     * them: its own artifact and those of its dependencies, transitively, each counted once, where distinct means a
     * different <code>groupId:artifactId:type[:classifier]:version</code>. A dependency that Maven resolves once, below
     * another node, counts for every node that depends on it, so the totals of two branches can overlap and the total
     * of the project, which is the size of all distinct dependencies, is less than the sum of the totals of its
     * dependencies. The totals are computed on the verbose tree even without <code>verbose</code>, and on the tree
     * before <code>includes</code> and <code>excludes</code> filter it. An artifact that cannot be resolved is shown as
     * <code>(size unknown)</code> and counts as zero. A size is shown with the units B, KB, MB, GB and TB, where 1 KB
     * is 1024 bytes.
     * <p>
     * The sizes are shown for the output types <code>text</code>, as in
     * <code>+- org.foo:foo-all:jar:2.0:compile (200 KB, total 1.5 MB)</code>, and <code>json</code>, as the numbers
     * <code>size</code> and <code>totalSize</code> in bytes. The other output types ignore this parameter with a
     * warning and download nothing.
     *
     * @since 3.12.0
     */
    @Parameter(property = "showSizes", defaultValue = "false")
    private boolean showSizes;

    /**
     * The computed dependency tree root node of the Maven project.
     */
    private DependencyNode rootNode;

    /**
     * Whether to append outputs into the output file or overwrite it. For <code>outputType=dot</code> the output
     * of each execution, as in a multi-module build, is added to the one graph in the file instead of starting
     * another one. The merge is serialized within one Maven JVM only, not across processes. A file left by a
     * previous build is extended, so delete it first.
     *
     * @since 2.2
     */
    @Parameter(property = "appendOutput", defaultValue = "false")
    private boolean appendOutput;

    /**
     * Skip plugin execution completely.
     *
     * @since 2.7
     */
    @Parameter(property = "skip", defaultValue = "false")
    private boolean skip;

    // Mojo methods -----------------------------------------------------------

    /*
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    @Override
    public void execute() throws MojoException {
        if (isSkip()) {
            log.info("Skipping plugin execution");
            return;
        }

        try {
            Predicate<Dependency> artifactFilter = createResolvingArtifactFilter();

            // the plain tree is what Maven resolves, consistent with the Maven version running; verbose keeps omitted
            // nodes
            rootNode = dependencyTreeBuilder.build(session, project, artifactFilter, verbose);

            boolean joinDotGraphs = outputFile != null && appendOutput && "dot".equals(outputType);
            DependencyNode completeRoot = null;
            if (showSizes) {
                if (isSizesOutputType()) {
                    // the totals of the plain tree count what a node brings in, also where Maven kept it elsewhere
                    completeRoot =
                            verbose ? rootNode : dependencyTreeBuilder.build(session, project, artifactFilter, true);
                } else {
                    log.warn("showSizes is ignored for outputType " + outputType + ", it applies to text and json");
                }
            }

            String dependencyTreeString = serializeDependencyTree(rootNode, completeRoot, !joinDotGraphs);

            if (outputFile != null) {
                String encoding = Objects.toString(outputEncoding, "UTF-8");
                if (joinDotGraphs) {
                    addToDotGraph(dependencyTreeString, rootNode.toNodeString(), outputFile, encoding);
                } else {
                    DependencyUtil.write(dependencyTreeString, outputFile, this.appendOutput, encoding);
                }

                log.info("Wrote dependency tree to: " + outputFile);
            } else {
                DependencyUtil.log(dependencyTreeString, log);
            }
        } catch (DependencyResolverException exception) {
            throw new MojoException("Cannot build project dependency graph", exception);
        } catch (IOException exception) {
            throw new MojoException("Cannot serialize project dependency graph", exception);
        }
    }

    // public methods ---------------------------------------------------------

    /**
     * Gets the Maven project used by this mojo.
     *
     * @return the Maven project
     */
    public Project getProject() {
        return project;
    }

    /**
     * Gets the computed dependency graph root node for the Maven project.
     *
     * @return the dependency tree root node
     */
    public DependencyNode getDependencyGraph() {
        return rootNode;
    }

    /**
     * @return {@link #skip}
     */
    public boolean isSkip() {
        return skip;
    }

    /**
     * @param skip {@link #skip}
     */
    public void setSkip(boolean skip) {
        this.skip = skip;
    }

    // private methods --------------------------------------------------------

    /**
     * Gets the artifact filter to use when resolving the dependency tree.
     *
     * @return the artifact filter
     */
    private Predicate<Dependency> createResolvingArtifactFilter() {
        Predicate<Dependency> filter;

        // filter scope
        if (scope != null) {
            log.debug("+ Resolving dependency tree for scope '" + scope + "'");

            filter = new ScopeArtifactFilter(scope);
        } else {
            filter = null;
        }

        return filter;
    }

    /**
     * Serializes the specified dependency tree to a string.
     *
     * @param theRootNode the dependency tree root node to serialize
     * @param completeRoot the verbose tree to compute the sizes on, or <code>null</code> to show no sizes
     * @return the serialized dependency tree
     */
    private String serializeDependencyTree(
            DependencyNode theRootNode, DependencyNode completeRoot, boolean dotGraphEnvelope) {
        StringWriter writer = new StringWriter();

        DependencyNode displayed =
                theRootNode.filter(null, createExcludesDependencyNodeFilter(), createIncludesDependencyNodeFilter());
        if (displayed != null) {
            if (completeRoot != null) {
                annotateSizes(displayed, completeRoot);
            }
            displayed.accept(
                    dotGraphEnvelope
                            ? getSerializingDependencyNodeVisitor(writer)
                            : new DOTDependencyNodeVisitor(writer, false));
        }

        return writer.toString();
    }

    /**
     * Computes the sizes of the artifacts of the displayed tree and of the complete tree, which means resolving the
     * artifacts that are not resolved yet.
     */
    private void annotateSizes(DependencyNode displayed, DependencyNode complete) {
        Map<String, DependencyNode> nodes = DependencySizes.nodes(displayed, complete);
        Map<String, Long> sizes = new HashMap<>();
        for (Map.Entry<String, DependencyNode> entry : nodes.entrySet()) {
            DependencyNode node = entry.getValue();
            File file = DependencySizes.localFile(node);
            if (file == null) {
                try {
                    List<RemoteRepository> repositories = node.getRepositories().isEmpty()
                            ? session.getService(ProjectManager.class).getRemoteProjectRepositories(project)
                            : node.getRepositories();
                    DownloadedArtifact resolved =
                            session.resolveArtifact(node.getArtifact().toCoordinates(), repositories);
                    file = resolved.getPath().toFile();
                } catch (RuntimeException e) {
                    log.debug("No size for " + node.getArtifact() + ": " + e.getMessage());
                }
            }
            if (file != null && file.isFile()) {
                sizes.put(entry.getKey(), file.length());
            } else if (file != null) {
                log.debug("No size for " + node.getArtifact() + ", not a file: " + file);
            }
        }

        int unknown = nodes.size() - sizes.size();
        if (unknown > 0) {
            log.warn("The size of " + unknown + (unknown > 1 ? " artifacts is" : " artifact is")
                    + " unknown, counted as zero in the totals");
        }

        DependencySizes.annotate(displayed, complete, sizes);
    }

    private boolean isSizesOutputType() {
        return !("dot".equals(outputType)
                || "graphml".equals(outputType)
                || "tgf".equals(outputType)
                || "mermaid".equals(outputType));
    }

    /**
     * Adds the edges of a tree to the DOT graph in the file, so that the trees of the modules of a reactor build end
     * up in one graph. A file that does not hold a graph yet gets one, named after the root of this tree.
     */
    private static synchronized void addToDotGraph(String edges, String rootLabel, File file, String encoding)
            throws IOException {
        Charset charset = Charset.forName(encoding);
        String existing = file.isFile() ? new String(Files.readAllBytes(file.toPath()), charset) : "";
        int close = existing.lastIndexOf('}');
        if (existing.trim().isEmpty()) {
            DependencyUtil.write(
                    DOTDependencyNodeVisitor.graphStart(rootLabel) + edges + DOTDependencyNodeVisitor.graphEnd(),
                    file,
                    false,
                    encoding);
        } else if (existing.contains("digraph")
                && close >= 0
                && existing.substring(close + 1).trim().isEmpty()) {
            String open = existing.substring(0, close).replaceAll("\\s+$", "");
            DependencyUtil.write(
                    open + System.lineSeparator() + edges + DOTDependencyNodeVisitor.graphEnd(), file, false, encoding);
        } else {
            // not a graph written by this goal, append as before
            DependencyUtil.write(
                    DOTDependencyNodeVisitor.graphStart(rootLabel) + edges + DOTDependencyNodeVisitor.graphEnd(),
                    file,
                    true,
                    encoding);
        }
    }

    /**
     * @param writer {@link Writer}
     * @return {@link DependencyNodeVisitor}
     */
    public DependencyNodeVisitor getSerializingDependencyNodeVisitor(Writer writer) {
        if ("graphml".equals(outputType)) {
            return new GraphmlDependencyNodeVisitor(writer);
        } else if ("tgf".equals(outputType)) {
            return new TGFDependencyNodeVisitor(writer);
        } else if ("dot".equals(outputType)) {
            return new DOTDependencyNodeVisitor(writer);
        } else if ("json".equals(outputType)) {
            return new JsonDependencyNodeVisitor(writer, project.getPackaging().id());
        } else if ("mermaid".equals(outputType)) {
            return new MermaidDependencyNodeVisitor(writer);
        } else {
            return new SerializingDependencyNodeVisitor(writer, toGraphTokens(tokens));
        }
    }

    /**
     * Gets the graph tokens instance for the specified name.
     *
     * @param theTokens the graph tokens name
     * @return the <code>GraphTokens</code> instance
     */
    private GraphTokens toGraphTokens(String theTokens) {
        GraphTokens graphTokens;

        if ("whitespace".equals(theTokens)) {
            log.debug("+ Using whitespace tree tokens");

            graphTokens = SerializingDependencyNodeVisitor.WHITESPACE_TOKENS;
        } else if ("extended".equals(theTokens)) {
            log.debug("+ Using extended tree tokens");

            graphTokens = SerializingDependencyNodeVisitor.EXTENDED_TOKENS;
        } else {
            graphTokens = SerializingDependencyNodeVisitor.STANDARD_TOKENS;
        }

        return graphTokens;
    }

    /**
     * Gets the dependency node filter to use when serializing the dependency graph.
     *
     * @return the dependency node filter, or <code>null</code> if none required
     */
    private Predicate<DependencyNode> createIncludesDependencyNodeFilter() {
        if (includes != null && !includes.isEmpty()) {
            log.debug("+ Filtering dependency tree by artifact include patterns: " + includes);

            return rootAware(new StrictPatternIncludesArtifactFilter(includes));
        }

        return null;
    }

    private Predicate<DependencyNode> createExcludesDependencyNodeFilter() {
        if (excludes != null && !excludes.isEmpty()) {
            log.debug("+ Filtering dependency tree by artifact exclude patterns: " + excludes);

            return rootAware(new StrictPatternExcludesArtifactFilter(excludes));
        }

        return null;
    }

    /**
     * The root node has no dependency in the Maven 4 API, but Maven 3 had the project artifact there, which the include
     * and exclude patterns are matched against too: a pattern that does not match the project leaves an empty tree.
     * The project is the root, of the project's packaging type.
     */
    private Predicate<DependencyNode> rootAware(Predicate<Dependency> filter) {
        return node -> {
            Dependency dependency = node.getDependency();
            if (dependency == null) {
                dependency = ResolvedDependency.of(
                        session, node.getArtifact(), project.getPackaging().id(), DependencyScope.COMPILE, null);
            }
            return filter.test(dependency);
        };
    }
}
