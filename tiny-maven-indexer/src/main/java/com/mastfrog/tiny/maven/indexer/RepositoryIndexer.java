package com.mastfrog.tiny.maven.indexer;

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
import com.google.inject.Inject;
import com.mastfrog.giulius.ShutdownHookRegistry;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.maven.index.*;
import org.apache.maven.index.context.IndexCreator;
import org.apache.maven.index.context.IndexingContext;
import static java.util.Arrays.asList;
import javax.inject.Named;

/**
 * This class provides means to index and search for artifacts in a repository
 * on the file system.
 *
 * @author mtodorov
 */
public class RepositoryIndexer implements Runnable {

    private final Indexer indexer;

    private final Scanner scanner;

    private final IndexingContext indexingContext;

    @Inject
    public RepositoryIndexer(Indexer indexer, Scanner scanner, @Named(Main.SETTINGS_KEY_REPOSITORY_ID) String repositoryId, List<IndexCreator> indexers, @Named(Main.SETTINGS_KEY_REPOSITORY_BASE_DIR) String repositoryBaseDir, @Named(Main.SETTINGS_KEY_INDEX_DIR) String indexDirName, ShutdownHookRegistry reg) throws IOException {
        File repositoryBasedir = new File(repositoryBaseDir);
        File indexDir = new File(indexDirName);
        if (!repositoryBasedir.exists()) {
            throw new IOException(Main.SETTINGS_KEY_REPOSITORY_BASE_DIR + " does not exist: " + repositoryBaseDir);
        } else if (!repositoryBasedir.isDirectory()) {
            throw new IOException(Main.SETTINGS_KEY_REPOSITORY_BASE_DIR + " is not a folder: " + repositoryBaseDir);
        }
        if (!indexDir.exists()) {
            if (!indexDir.mkdirs()) {
                throw new IOException("Could not create " + indexDir);
            }
        } else if (!indexDir.isDirectory()) {
            throw new IOException("Index dir is not a folder: " + indexDir);
        }
        this.scanner = scanner;
        this.indexer = indexer;
        this.indexingContext = indexer.createIndexingContext(repositoryId + "/ctx",
                repositoryId,
                repositoryBasedir,
                indexDir,
                null,
                null,
                true, // if context should be searched in non-targeted mode.
                true, // if indexDirectory is known to contain (or should contain)
                // valid Maven Indexer lucene index, and no checks needed to be
                // performed, or, if we want to "stomp" over existing index
                // (unsafe to do!).
                indexers);
    }

    public void close()
            throws IOException {
        indexer.closeIndexingContext(indexingContext, false);
    }

    public void close(boolean deleteFiles)
            throws IOException {
        indexingContext.close(deleteFiles);
    }

    public void delete(final Collection<ArtifactInfo> artifacts)
            throws IOException {
        final List<ArtifactContext> delete = new ArrayList<ArtifactContext>();
        for (final ArtifactInfo artifact : artifacts) {
            System.out.println("Deleting artifact: " + artifact + "; ctx id: " + indexingContext.getId() + "; idx dir: " + indexingContext.getIndexDirectory());
            delete.add(new ArtifactContext(null, null, null, artifact, null));
        }

        indexer.deleteArtifactsFromIndex(delete, indexingContext);
    }

    public int index() {
        return index(null);
    }

    public int index(final File startingPath) {
        final ScanningResult scan = scanner.scan(new ScanningRequest(indexingContext,
                new ReindexArtifactScanningListener(),
                startingPath == null ? "."
                        : startingPath.getPath()));
        return scan.getTotalFiles();
    }

    @Override
    public void run() {
        try {
            close();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private class ReindexArtifactScanningListener
            implements ArtifactScanningListener {

        int totalFiles = 0;
        private IndexingContext context;

        @Override
        public void scanningStarted(final IndexingContext context) {
            this.context = context;
        }

        @Override
        public void scanningFinished(final IndexingContext context,
                final ScanningResult result) {
            result.setTotalFiles(totalFiles);
            System.out.println("Scanning finished; total files: " + result.getTotalFiles() + "; has exception: "
                    + result.hasExceptions());
        }

        @Override
        public void artifactError(final ArtifactContext ac,
                final Exception ex) {
            ex.printStackTrace();
        }

        @Override
        public void artifactDiscovered(final ArtifactContext ac) {
            try {
                System.out.println("Adding artifact " + ac.getArtifactInfo().getGroupId()
                        + ":" + ac.getArtifactInfo().getArtifactId()
                        + ":" + ac.getArtifactInfo().getVersion());
                indexer.addArtifactsToIndex(asList(ac), context);
                totalFiles++;
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }
}
