package dev.junxiex.mikuprotect;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * Downloads the JDBC drivers and HikariCP at startup so the plugin jar stays small.
 */
public final class MikuProtectLoader implements PluginLoader {

    @Override
    public void classloader(PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        // 显式声明 Maven Central 镜像，Folia 的 loader 默认仓库不包含依赖；该地址是 Paper 认可
        // 的 MAVEN_CENTRAL_DEFAULT_MIRROR，不会触发 Maven Central ToS 警告。
        resolver.addRepository(new RemoteRepository.Builder("central", "default",
                MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());
        resolver.addDependency(new Dependency(new DefaultArtifact("org.duckdb:duckdb_jdbc:1.4.5.0"), null));
        resolver.addDependency(new Dependency(new DefaultArtifact("org.mariadb.jdbc:mariadb-java-client:3.5.3"), null));
        resolver.addDependency(new Dependency(new DefaultArtifact("com.zaxxer:HikariCP:7.0.2"), null));
        classpathBuilder.addLibrary(resolver);
    }
}
