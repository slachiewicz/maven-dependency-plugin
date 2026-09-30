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
package org.apache.maven.plugins.dependency.utils;

import javax.inject.Named;
import javax.inject.Provider;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import org.apache.maven.api.Session;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.services.Lookup;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.UnArchiver;
import org.codehaus.plexus.archiver.manager.ArchiverManager;
import org.codehaus.plexus.archiver.manager.DefaultArchiverManager;
import org.codehaus.plexus.archiver.manager.NoSuchArchiverException;
import org.codehaus.plexus.archiver.zip.ZipUnArchiver;
import org.codehaus.plexus.components.io.filemappers.FileMapper;
import org.codehaus.plexus.components.io.fileselectors.IncludeExcludeFileSelector;

/**
 * Provide unpack method in one place for {@link org.apache.maven.plugins.dependency.fromConfiguration.UnpackMojo}
 * and {@link org.apache.maven.plugins.dependency.fromDependencies.UnpackDependenciesMojo}
 */
public class UnpackUtil {

    private static final java.nio.charset.Charset UTF_8 = java.nio.charset.StandardCharsets.UTF_8;

    /**
     * To look up Archiver/UnArchiver implementations.
     */
    private final ArchiverManager archiverManager;

    /**
     * Creates the helper with an {@link ArchiverManager}. {@code ArchiverManager} is a Sisu component of
     * plexus-archiver, which a Maven 4 API mojo cannot have injected: Maven does not scan the realm of such a plugin
     * for Sisu components, and the {@link Lookup} service does not find it there. The manager is therefore built here
     * out of the {@code UnArchiver} implementations that plexus-archiver lists in its Sisu index.
     *
     * @param session the session
     * @return the helper
     * @throws MojoException if the archiver manager is not available
     */
    public static UnpackUtil create(Session session) throws MojoException {
        try {
            return new UnpackUtil(session.getService(Lookup.class).lookup(ArchiverManager.class));
        } catch (RuntimeException e) {
            // not a component Maven knows of, which is the normal case
        }
        return new UnpackUtil(createArchiverManager());
    }

    private static ArchiverManager createArchiverManager() throws MojoException {
        Map<String, Provider<UnArchiver>> unArchivers = new HashMap<>();
        ClassLoader classLoader = UnpackUtil.class.getClassLoader();
        try {
            Enumeration<URL> indexes = classLoader.getResources("META-INF/sisu/javax.inject.Named");
            while (indexes.hasMoreElements()) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(indexes.nextElement().openStream(), UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        registerUnArchiver(classLoader, line.trim(), unArchivers);
                    }
                }
            }
        } catch (IOException e) {
            throw new MojoException("Cannot read the Sisu index of plexus-archiver", e);
        }
        return new DefaultArchiverManager(new HashMap<>(), unArchivers, new HashMap<>());
    }

    private static void registerUnArchiver(
            ClassLoader classLoader, String className, Map<String, Provider<UnArchiver>> unArchivers) {
        if (className.isEmpty() || className.startsWith("#")) {
            return;
        }
        try {
            Class<?> type = Class.forName(className, false, classLoader);
            Named named = type.getAnnotation(Named.class);
            if (named != null && UnArchiver.class.isAssignableFrom(type)) {
                Constructor<?> constructor = type.getConstructor();
                unArchivers.put(named.value(), () -> {
                    try {
                        return (UnArchiver) constructor.newInstance();
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException("Cannot create " + className, e);
                    }
                });
            }
        } catch (ClassNotFoundException | NoClassDefFoundError | NoSuchMethodException e) {
            // an index entry for something that is not loadable or not an unarchiver, e.g. an optional dependency
        }
    }

    /**
     * Default constructor.
     *
     * @param archiverManager an archiver {@link ArchiverManager} to use
     */
    public UnpackUtil(ArchiverManager archiverManager) {
        this.archiverManager = archiverManager;
    }

    /**
     * @param file              file to unpack
     * @param type              file / artifact type
     * @param location          the location
     * @param includes          includes list
     * @param excludes          excludes list
     * @param encoding          the encoding
     * @param ignorePermissions ignore permissions
     * @param fileMappers       {@link FileMapper}s to be used for rewriting each target path, or {@code null} if no
     *                          rewriting
     *                          shall happen
     * @param logger            a Mojo logger
     * @throws MojoException in case of an error
     */
    public void unpack(
            File file,
            String type,
            File location,
            String includes,
            String excludes,
            String encoding,
            boolean ignorePermissions,
            FileMapper[] fileMappers,
            Log logger)
            throws MojoException {
        try {
            logUnpack(logger, file, location, includes, excludes);

            location.mkdirs();
            if (!location.exists()) {
                throw new MojoException("Location to write unpacked files to could not be created: " + location);
            }

            if (file.isDirectory()) {
                // usual case is a future jar packaging, but there are special cases: classifier and other packaging
                throw new MojoException("Artifact has not been packaged yet. When used on reactor artifact, "
                        + "unpack should be executed after packaging: see MDEP-98.");
            }

            UnArchiver unArchiver;

            try {
                unArchiver = archiverManager.getUnArchiver(type);
                logger.debug("Found unArchiver: " + unArchiver.getClass().getName() + " by type: " + type);
            } catch (NoSuchArchiverException e) {
                unArchiver = archiverManager.getUnArchiver(file);
                logger.debug("Found unArchiver: " + unArchiver.getClass().getName() + " by file extension: " + file);
            }

            if (encoding != null && unArchiver instanceof ZipUnArchiver) {
                ((ZipUnArchiver) unArchiver).setEncoding(encoding);
                logger.info("Unpacks '" + type + "' with encoding '" + encoding + "'.");
            }

            unArchiver.setIgnorePermissions(ignorePermissions);

            unArchiver.setSourceFile(file);

            unArchiver.setDestDirectory(location);

            if ((excludes != null && !excludes.isEmpty()) || (includes != null && !includes.isEmpty())) {
                // Create the selectors that will filter
                // based on include/exclude parameters
                // MDEP-47
                IncludeExcludeFileSelector[] selectors =
                        new IncludeExcludeFileSelector[] {new IncludeExcludeFileSelector()};

                if (excludes != null && !excludes.isEmpty()) {
                    selectors[0].setExcludes(excludes.split(","));
                }

                if (includes != null && !includes.isEmpty()) {
                    selectors[0].setIncludes(includes.split(","));
                }

                unArchiver.setFileSelectors(selectors);
            }

            unArchiver.setFileMappers(fileMappers);

            unArchiver.extract();
        } catch (NoSuchArchiverException e) {
            throw new MojoException("Unknown archiver type", e);
        } catch (ArchiverException e) {
            throw new MojoException("Error unpacking file: " + file + " to: " + location, e);
        }
    }

    private void logUnpack(Log logger, File file, File location, String includes, String excludes) {
        if (logger.isDebugEnabled()) {
            StringBuilder msg = new StringBuilder();
            msg.append("Unpacking ");
            msg.append(file);
            msg.append(" to ");
            msg.append(location);

            if (includes != null && excludes != null) {
                msg.append(" with includes \"");
                msg.append(includes);
                msg.append("\" and excludes \"");
                msg.append(excludes);
                msg.append("\"");
            } else if (includes != null) {
                msg.append(" with includes \"");
                msg.append(includes);
                msg.append("\"");
            } else if (excludes != null) {
                msg.append(" with excludes \"");
                msg.append(excludes);
                msg.append("\"");
            }

            logger.debug(msg.toString());
        }
    }
}
