package com.mastfrog.tiny.maven.indexer;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.name.Names;
import com.mastfrog.settings.Settings;
import com.mastfrog.settings.SettingsBuilder;
import java.io.File;
import java.io.IOException;

/**
 *
 * @author Tim Boudreau
 */
public class Main extends AbstractModule {

    public static final String SETTINGS_KEY_REPOSITORY_ID = "repositoryId";
    public static final String SETTINGS_KEY_REPOSITORY_BASE_DIR = "repositoryDir";
    public static final String SETTINGS_KEY_INDEX_DIR = "indexDir";
    private final Settings settings;

    public static void main(String[] args) throws IOException, Exception {
        Settings settings = new SettingsBuilder("tiny-maven-proxy")
                .add("application.name", "tiny-maven-proxy")
                // The repository id
                .add(SETTINGS_KEY_REPOSITORY_ID, "tiny")
                // Default to system temp dir and ~/.m2/repository for testing - these should be
                // set in tiny-maven-proxy.properties in /etc or working dir, or passed on the
                // command-line
                .add(SETTINGS_KEY_REPOSITORY_BASE_DIR, System.getProperty("user.home") + File.separatorChar + ".m2" + File.separatorChar + "repository")
                // .index/nexus-maven-repository-index.properties

                .add(SETTINGS_KEY_INDEX_DIR, "_") // by default, use $REPO_DIR/.index
                .addFilesystemAndClasspathLocations()
                .parseCommandLineArguments(args)
                .build();
        IndexerImpl impl = Guice.createInjector(new Main(settings)).getInstance(IndexerImpl.class);
        impl.start();
    }

    Main(Settings settings) {
        this.settings = settings;
    }

    @Override
    protected void configure() {
        bind(IndexerImpl.class).asEagerSingleton();
        bind(Settings.class).toInstance(settings);
        for (String s : new String[]{SETTINGS_KEY_INDEX_DIR, SETTINGS_KEY_REPOSITORY_BASE_DIR, SETTINGS_KEY_REPOSITORY_ID}) {
            bind(String.class).annotatedWith(Names.named(s)).toInstance(settings.getString(s));
        }
    }
}
