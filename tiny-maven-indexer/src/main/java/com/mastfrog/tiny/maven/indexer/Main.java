package com.mastfrog.tiny.maven.indexer;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Scopes;
import com.google.inject.TypeLiteral;
import com.mastfrog.giulius.Dependencies;
import com.mastfrog.guicy.annotations.Namespace;
import com.mastfrog.settings.Settings;
import com.mastfrog.settings.SettingsBuilder;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Provider;
import org.apache.maven.index.ArtifactContextProducer;
import org.apache.maven.index.DefaultArtifactContextProducer;
import org.apache.maven.index.DefaultIndexer;
import org.apache.maven.index.DefaultIndexerEngine;
import org.apache.maven.index.DefaultQueryCreator;
import org.apache.maven.index.DefaultScanner;
import org.apache.maven.index.DefaultSearchEngine;
import org.apache.maven.index.Indexer;
import org.apache.maven.index.IndexerEngine;
import org.apache.maven.index.QueryCreator;
import org.apache.maven.index.Scanner;
import org.apache.maven.index.SearchEngine;
import org.apache.maven.index.artifact.ArtifactPackagingMapper;
import org.apache.maven.index.artifact.DefaultArtifactPackagingMapper;
import org.apache.maven.index.context.IndexCreator;
import org.apache.maven.index.creator.JarFileContentsIndexCreator;
import org.apache.maven.index.creator.MavenPluginArtifactInfoIndexCreator;
import org.apache.maven.index.creator.MinimalArtifactInfoIndexCreator;

/**
 *
 * @author Tim Boudreau
 */
public class Main extends AbstractModule {

    public static final String SETTINGS_KEY_REPOSITORY_ID = "repositoryId";
    public static final String SETTINGS_KEY_REPOSITORY_BASE_DIR = "repositoryDir";
    public static final String SETTINGS_KEY_INDEX_DIR = "indexDir";

    public static void main(String[] args) throws IOException {
        Settings settings = new SettingsBuilder("tiny-maven-proxy")
                // The repository id
                .add(SETTINGS_KEY_REPOSITORY_ID, "tiny")
                // Default to system temp dir and ~/.m2/repository for testing - these should be
                // set in tiny-maven-proxy.properties in /etc or working dir, or passed on the
                // command-line
                .add(SETTINGS_KEY_REPOSITORY_BASE_DIR, System.getProperty("user.home") + File.separatorChar + ".m2" + File.separatorChar + "repository")
                .add(SETTINGS_KEY_INDEX_DIR, System.getProperty("java.io.tmpdir") + File.separatorChar + "maven-index")
                .addFilesystemAndClasspathLocations()
                .parseCommandLineArguments(args)
                .build();
        Dependencies deps = Dependencies.builder()
                .add(settings, Namespace.DEFAULT)
                .add(new Main())
                .build();
        RepositoryIndexer indexer = deps.getInstance(RepositoryIndexer.class);
        int count = indexer.index();
        System.out.println("Indexed " + count);
    }

    @Override
    protected void configure() {
        org.apache.maven.index.DefaultIndexer o;
        bind(Indexer.class).to(DefaultIndexer.class).in(Scopes.SINGLETON);
        bind(Scanner.class).to(DefaultScanner.class).in(Scopes.SINGLETON);
        bind(new TypeLiteral<List<IndexCreator>>() {
        }).toProvider(IndexCreatorListProvider.class);
        bind(IndexerEngine.class).to(DefaultIndexerEngine.class);
        bind(ArtifactContextProducer.class).to(DefaultArtifactContextProducer.class).in(Scopes.SINGLETON);
        bind(ArtifactPackagingMapper.class).to(DefaultArtifactPackagingMapper.class).in(Scopes.SINGLETON);
        bind(SearchEngine.class).to(DefaultSearchEngine.class).in(Scopes.SINGLETON);
        bind(QueryCreator.class).to(DefaultQueryCreator.class).in(Scopes.SINGLETON);
    }

    private static class IndexCreatorListProvider implements Provider<List<IndexCreator>> {

        private final Provider<MavenPluginArtifactInfoIndexCreator> three;
        private final Provider<JarFileContentsIndexCreator> two;
        private final Provider<MinimalArtifactInfoIndexCreator> one;
        private List<IndexCreator> creators;

        @Inject
        IndexCreatorListProvider(Provider<MinimalArtifactInfoIndexCreator> one, Provider<JarFileContentsIndexCreator> two, Provider<MavenPluginArtifactInfoIndexCreator> three) {
            this.one = one;
            this.two = two;
            this.three = three;
        }

        @Override
        public synchronized List<IndexCreator> get() {
            if (creators == null) {
                List<IndexCreator> result = new ArrayList<>(3);
                result.add(one.get());
                result.add(two.get());
                result.add(three.get());
                return creators = result;
            }
            return creators;
        }
    }
}
