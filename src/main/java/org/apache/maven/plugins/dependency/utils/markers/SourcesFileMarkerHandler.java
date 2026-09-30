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
package org.apache.maven.plugins.dependency.utils.markers;

import java.io.File;
import java.io.IOException;

import org.apache.maven.api.DownloadedDependency;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.plugins.dependency.utils.DependencyUtil;

/**
 * @author <a href="mailto:brianf@apache.org">Brian Fox</a>
 */
public class SourcesFileMarkerHandler extends DefaultFileMarkerHandler {

    boolean resolved;

    /**
     * @param markerFilesDirectory the marker files directory
     */
    public SourcesFileMarkerHandler(File markerFilesDirectory) {
        super(markerFilesDirectory);
    }

    /**
     * @param artifact {@link DownloadedDependency}
     * @param markerFilesDirectory marker files directory
     * @param isResolved true/false
     */
    public SourcesFileMarkerHandler(DownloadedDependency artifact, File markerFilesDirectory, boolean isResolved) {
        super(artifact, markerFilesDirectory);
        this.resolved = isResolved;
    }

    /**
     * Returns properly formatted File.
     *
     * @return file object for marker. The file is not guaranteed to exist.
     */
    @Override
    public File getMarkerFile() {
        return getMarkerFile(this.resolved);
    }

    /**
     * Get MarkerFile, exposed for unit testing purposes.
     *
     * @param res resolved or not
     * @return marker file for this artifact
     */
    protected File getMarkerFile(boolean res) {
        String suffix;
        if (res) {
            suffix = ".resolved";
        } else {
            suffix = ".unresolved";
        }

        return new File(
                this.markerFilesDirectory, DependencyUtil.getId(this.artifact).replace(':', '-') + suffix);
    }

    /**
     * Tests whether the file or directory denoted by this abstract pathname exists.
     *
     * @return <code>true</code> if and only if the file or directory denoted by this abstract pathname exists;
     *         <code>false</code> otherwise
     * @throws MojoException if a security manager exists and its <code>{@link
     *          java.lang.SecurityManager#checkRead(java.lang.String)}</code> method denies read access to the file or
     *             directory
     */
    @Override
    public boolean isMarkerSet() throws MojoException {
        File marker = getMarkerFile();

        File marker2 = getMarkerFile(!this.resolved);

        return marker.exists() || marker2.exists();
    }

    @Override
    public boolean isMarkerOlder(DownloadedDependency theArtifact) throws MojoException {
        File marker = getMarkerFile();
        if (marker.exists()) {
            return theArtifact.getPath().toFile().lastModified() > marker.lastModified();
        } else {
            marker = getMarkerFile(!this.resolved);
            if (marker.exists()) {
                return theArtifact.getPath().toFile().lastModified() > marker.lastModified();
            } else {
                // if the marker doesn't exist, we want to copy so assume it is
                // infinitely older
                return true;
            }
        }
    }

    @Override
    public void setMarker() throws MojoException {
        File marker = getMarkerFile();

        // get the other file if it exists.
        File clearMarker = getMarkerFile(!this.resolved);
        // create marker file
        try {
            marker.getParentFile().mkdirs();
        } catch (NullPointerException e) {
            // parent is null, ignore it.
        }

        try {
            marker.createNewFile();
            // clear the other file if it exists.
            if (clearMarker.exists()) {
                if (!clearMarker.delete()) {
                    clearMarker.deleteOnExit();
                }
            }
        } catch (IOException e) {
            throw new MojoException("Unable to create Marker: " + marker.getAbsolutePath(), e);
        }
    }

    /**
     * Deletes the file or directory denoted by this abstract pathname. If this pathname denotes a directory, then the
     * directory must be empty in order to be deleted.
     *
     * @return <code>true</code> if and only if the file or directory is successfully deleted; <code>false</code>
     *         otherwise
     * @throws SecurityException if a security manager exists and its <code>{@link
     *          java.lang.SecurityManager#checkDelete}</code> method denies delete access to the file
     */
    @Override
    public boolean clearMarker() throws MojoException {
        File marker = getMarkerFile();
        File marker2 = getMarkerFile(!this.resolved);
        boolean markResult = marker.delete();
        boolean mark2Result = marker2.delete();
        return markResult || mark2Result;
    }

    /**
     * @return returns the resolved
     */
    public boolean isResolved() {
        return this.resolved;
    }

    /**
     * @param isResolved the resolved to set
     */
    public void setResolved(boolean isResolved) {
        this.resolved = isResolved;
    }
}
