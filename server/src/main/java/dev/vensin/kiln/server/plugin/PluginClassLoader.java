package dev.vensin.kiln.server.plugin;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

/** Sees the server, its own jar and the jars of the plugins it depends on, but not unrelated plugins. */
final class PluginClassLoader extends URLClassLoader {

    private final List<PluginClassLoader> dependencies;

    PluginClassLoader(URL jar, ClassLoader server, List<PluginClassLoader> dependencies) {
        super(new URL[] {jar}, server);
        this.dependencies = dependencies;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            return super.findClass(name);
        } catch (ClassNotFoundException notInThisJar) {
            for (PluginClassLoader dependency : dependencies) {
                try {
                    return dependency.loadClass(name);
                } catch (ClassNotFoundException next) {
                    // try the next dependency
                }
            }
            throw notInThisJar;
        }
    }
}
