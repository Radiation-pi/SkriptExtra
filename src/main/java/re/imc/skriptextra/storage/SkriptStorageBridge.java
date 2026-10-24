package re.imc.skriptextra.storage;

import ch.njol.skript.variables.Variables;
import ch.njol.skript.variables.VariablesStorage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Isolates the two non-public Skript hooks; verify their exact signatures before registering. */
final class SkriptStorageBridge {
    private static final Method LOADED;
    private static final Field WRITE_THREAD;
    static {
        try {
            LOADED = Variables.class.getDeclaredMethod("variableLoaded", String.class, Object.class, VariablesStorage.class);
            WRITE_THREAD = VariablesStorage.class.getDeclaredField("writeThread");
            if (!LOADED.trySetAccessible() || !WRITE_THREAD.trySetAccessible()
                    || WRITE_THREAD.getType() != Thread.class || LOADED.getReturnType() != boolean.class)
                throw new ReflectiveOperationException("Incompatible Skript storage hooks");
        } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }

    static void verify() { /* Triggers initialization before variable loading. */ }

    static void loaded(String name, Object value, VariablesStorage source) throws ReflectiveOperationException {
        LOADED.invoke(null, name, value, source);
    }

    static void joinSaveThread(VariablesStorage storage) throws ReflectiveOperationException, InterruptedException {
        Thread thread = (Thread) WRITE_THREAD.get(storage);
        thread.join(5000);
        if (thread.isAlive()) throw new IllegalStateException("Skript save thread did not finish");
    }
}
