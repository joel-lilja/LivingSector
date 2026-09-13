package livingsector.campaign;

import java.net.URL;
import java.net.URLClassLoader;
import livingsector.LivingSectorPlugin;

/** Targeted model of the game's blocked reflection classes; not a complete engine sandbox. */
final class ScriptRestrictionLoader extends URLClassLoader {
    ScriptRestrictionLoader() {
        super(new URL[]{LivingSectorPlugin.class.getProtectionDomain().getCodeSource().getLocation()},
                LivingSectorPlugin.class.getClassLoader());
    }
    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith("java.lang.reflect.")) {
            throw new SecurityException("File access and reflection are not allowed to scripts. (" + name + ")");
        }
        if (!name.startsWith("livingsector.")) return super.loadClass(name, resolve);
        Class<?> type = findLoadedClass(name);
        if (type == null) type = findClass(name);
        if (resolve) resolveClass(type);
        return type;
    }
}
