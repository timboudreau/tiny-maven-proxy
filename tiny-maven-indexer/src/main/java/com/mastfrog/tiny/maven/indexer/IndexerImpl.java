/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.mastfrog.tiny.maven.indexer;

import com.google.inject.Inject;
import com.mastfrog.settings.Settings;
import static com.mastfrog.tiny.maven.indexer.Main.SETTINGS_KEY_INDEX_DIR;
import static com.mastfrog.tiny.maven.indexer.Main.SETTINGS_KEY_REPOSITORY_BASE_DIR;
import static com.mastfrog.tiny.maven.indexer.Main.SETTINGS_KEY_REPOSITORY_ID;
import com.mastfrog.util.preconditions.ConfigurationError;
import com.mastfrog.util.strings.Strings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.inject.Named;

/**
 *
 * @author Tim Boudreau
 */
public class IndexerImpl {

    private final Path indexPath;
    private final Path repoPath;
    private final String id;

    @Inject
    IndexerImpl(@Named(SETTINGS_KEY_REPOSITORY_BASE_DIR) String repoDir, @Named(SETTINGS_KEY_INDEX_DIR) String indexDir,
            @Named(SETTINGS_KEY_REPOSITORY_ID) String id) throws Exception {
        repoPath = Paths.get(repoDir);
        if (!Files.exists(repoPath)) {
            throw new ConfigurationError("Repo path " + repoDir + " does not exist");
        }
        if ("_".equals(indexDir)) {
            indexDir = repoPath.resolve(Paths.get(".index")).toString();
        }
        indexPath = Paths.get(indexDir);
        if (!Files.exists(indexPath.getParent())) {
            Files.createDirectories(indexPath.getParent());
        }
        this.id = id;
        System.out.println("INDEX " + repoPath + " to index " + indexPath + " for repo " + id);
    }

    IndexerImpl(Settings settings) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    void start() throws Exception {
        // export REPODIR=/path/to/your/local/repo/ && 
        // java org.sonatype.nexus.index.cli.NexusIndexerCli -r $REPODIR -i $REPODIR/.index -d $REPODIR/.index -n localrepo

        String[] args = new String[]{
            "-r", repoPath.toString(),
            "-i", indexPath.toString(),
            "-d", indexPath.toString(),
            "-n", id
        };
        System.out.println("WILL RUN " + Strings.join(' ', args));
        org.sonatype.nexus.index.cli.NexusIndexerCli.main(args);
    }
}
