/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package airlift.util;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import org.mozilla.javascript.commonjs.module.ModuleScript;
import org.mozilla.javascript.commonjs.module.ModuleScriptProvider;
import org.mozilla.javascript.commonjs.module.provider.CachingModuleScriptProviderBase;
import org.mozilla.javascript.commonjs.module.ModuleScope;

/**
 * Implements the require() function as defined by
 * <a href="http://wiki.commonjs.org/wiki/Modules/1.1">Common JS modules</a>.
 * <h1>Thread safety</h1>
 * You will ordinarily create one instance of require() for every top-level
 * scope. This ordinarily means one instance per program execution, except if
 * you use shared top-level scopes and installing most objects into them.
 * Module loading is thread safe, so using a single require() in a shared
 * top-level scope is also safe.
 * <h1>Creation</h1>
 * If you need to create many otherwise identical require() functions for
 * different scopes, you might want to use {@link RequireBuilder} for
 * convenience.
 * <h1>Making it available</h1>
 * In order to make the require() function available to your JavaScript
 * program, you need to invoke either {@link #install(Scriptable)} or
 * {@link #requireMain(Context, String)}.
 * @author Attila Szegedi
 * @version $Id: Require.java,v 1.4 2011/04/07 20:26:11 hannes%helma.at Exp $
 */
public class SharedRequire
{
    private static final long serialVersionUID = 1L;

    private final ModuleScriptProvider moduleScriptProvider;
    private final Scriptable nativeScope;
    private final Scriptable paths;
    protected final boolean sandboxed;
    private final Script preExec;
    private final Script postExec;
    private String mainModuleId = null;
	private Scriptable mainExports;
	
    // Modules that completed loading; visible to all threads
    private final Map<String, Scriptable> exportedModuleInterfaces =
        new ConcurrentHashMap<String, Scriptable>();
    private final Object loadLock = new Object();
    // Modules currently being loaded on the thread. Used to resolve circular
    // dependencies while loading.
    private static final ThreadLocal<Map<String, Scriptable>>
        loadingModuleInterfaces = new ThreadLocal<Map<String,Scriptable>>();

    /**
     * Creates a new instance of the require() function. Upon constructing it,
     * you will either want to install it in the global (or some other) scope
     * using {@link #install(Scriptable)}, or alternatively, you can load the
     * program's main module using {@link #requireMain(Context, String)} and
     * then act on the main module's exports.
     * @param cx the current context
     * @param nativeScope a scope that provides the standard native JavaScript
     * objects.
     * @param moduleScriptProvider a provider for module scripts
     * @param preExec an optional script that is executed in every module's
     * scope before its module script is run.
     * @param postExec an optional script that is executed in every module's
     * scope after its module script is run.
     * @param sandboxed if set to true, the require function will be sandboxed.
     * This means that it doesn't have the "paths" property, and also that the
     * modules it loads don't export the "module.uri" property.
     */
    public SharedRequire(Context cx, Scriptable nativeScope,
            ModuleScriptProvider moduleScriptProvider, Script preExec,
            Script postExec, boolean sandboxed) {
        this.moduleScriptProvider = moduleScriptProvider;
        this.nativeScope = nativeScope;
		this.sandboxed = sandboxed;
        this.preExec = preExec;
		this.postExec = postExec;
        if(!sandboxed) {
            paths = cx.newArray(nativeScope, 0);
        }
        else {
            paths = null;
        }
    }

    protected Scriptable getExportedModuleInterface(Require _require, Context cx, String id, URI uri, URI base, boolean isMain, boolean _cachingEnabled)
	{
		Scriptable exports = null;
		Map<String, Scriptable> threadLoadingModules = null;
		
		if (_cachingEnabled == true)
		{
			// Check if the requested module is already completely loaded
			exports = exportedModuleInterfaces.get(id);
			if(exports != null) {
				if(isMain) {
					throw new IllegalStateException(
							"Attempt to set main module after it was loaded");
				}

				System.out.println("MODULE is cached: " + id);
				return exports;
			}
			// Check if it is currently being loaded on the current thread
			// (supporting circular dependencies).
			threadLoadingModules = loadingModuleInterfaces.get();
			if(threadLoadingModules != null) {
				exports = threadLoadingModules.get(id);
				if(exports != null) {
					System.out.println("MODULE is currently being loaded: " + id);
					return exports;
				}
			}
		}

        // The requested module is neither already loaded, nor is it being
        // loaded on the current thread. End of fast path. We must synchronize
        // now, as we have to guarantee that at most one thread can load
        // modules at any one time. Otherwise, two threads could end up
        // attempting to load two circularly dependent modules in opposite
        // order, which would lead to either unacceptable non-determinism or
        // deadlock, depending on whether we underprotected or overprotected it
        // with locks.
        synchronized(loadLock) {
            // Recheck if it is already loaded - other thread might've
            // completed loading it just as we entered the synchronized
			// block.
			if (_cachingEnabled == true)
			{
				exports = exportedModuleInterfaces.get(id);
			}
			
			if(exports != null) {
				System.out.println("MODULE is cached: " + id);
                return exports;
			}

			System.out.println("module is NOT CACHED: " + id);
			
            // Nope, still not loaded; we're loading it then.
            final ModuleScript moduleScript = getModule(cx, id, uri, base);
            if (sandboxed && !moduleScript.isSandboxed()) {
                throw ScriptRuntime.throwError(cx, nativeScope, "Module \""
                        + id + "\" is not contained in sandbox.");
            }
            exports = cx.newObject(nativeScope);
            // Are we the outermost locked invocation on this thread?
            final boolean outermostLocked = threadLoadingModules == null;
            if(outermostLocked) {
                threadLoadingModules = new HashMap<String, Scriptable>();
                loadingModuleInterfaces.set(threadLoadingModules);
            }
            // Must make the module exports available immediately on the
            // current thread, to satisfy the CommonJS Modules/1.1 requirement
            // that "If there is a dependency cycle, the foreign module may not
            // have finished executing at the time it is required by one of its
            // transitive dependencies; in this case, the object returned by
            // "require" must contain at least the exports that the foreign
            // module has prepared before the call to require that led to the
            // current module's execution."
            threadLoadingModules.put(id, exports);
            try {
                // Support non-standard Node.js feature to allow modules to
                // replace the exports object by setting module.exports.
                Scriptable newExports = executeModuleScript(_require, cx, id, exports,
                        moduleScript, isMain, _cachingEnabled);
                if (exports != newExports) {
                    threadLoadingModules.put(id, newExports);
                    exports = newExports;
                }
            }
            catch(RuntimeException e) {
                // Throw loaded module away if there was an exception
                threadLoadingModules.remove(id);
                throw e;
            }
            finally {
                if(outermostLocked) {
                    // Make loaded modules visible to other threads only after
                    // the topmost triggering load has completed. This strategy
                    // (compared to the one where we'd make each module
                    // globally available as soon as it loads) prevents other
                    // threads from observing a partially loaded circular
                    // dependency of a module that completed loading.
                    exportedModuleInterfaces.putAll(threadLoadingModules);
                    loadingModuleInterfaces.set(null);
                }
            }
        }
        return exports;
    }

    private Scriptable executeModuleScript(Require _require, Context cx, String id,
            Scriptable exports, ModuleScript moduleScript, boolean isMain, boolean _cachingEnabled)
    {
        final ScriptableObject moduleObject = (ScriptableObject)cx.newObject(
                nativeScope);
        URI uri = moduleScript.getUri();
        URI base = moduleScript.getBase();
        defineReadOnlyProperty(moduleObject, "id", id);
        if(!sandboxed) {
            defineReadOnlyProperty(moduleObject, "uri", uri.toString());
        }
        final Scriptable executionScope = new ModuleScope(nativeScope, uri, base);
        // Set this so it can access the global JS environment objects.
        // This means we're currently using the "MGN" approach (ModuleScript
        // with Global Natives) as specified here:
        // <http://wiki.commonjs.org/wiki/Modules/ProposalForNativeExtension>
        executionScope.put("exports", executionScope, exports);
        executionScope.put("module", executionScope, moduleObject);
        moduleObject.put("exports", moduleObject, exports);
		install(_require, executionScope, _cachingEnabled);
        if(isMain) {
            defineReadOnlyProperty(_require, "main", moduleObject);
        }
        executeOptionalScript(preExec, cx, executionScope);
        moduleScript.getScript().exec(cx, executionScope);
        executeOptionalScript(postExec, cx, executionScope);
        return ScriptRuntime.toObject(nativeScope,
                ScriptableObject.getProperty(moduleObject, "exports"));
	}

	public void install(Require _require, Scriptable scope, boolean _cachingEnabled) {
		ScriptableObject.putProperty(scope, "require", _require);
	}

    private static void executeOptionalScript(Script script, Context cx,
            Scriptable executionScope)
    {
        if(script != null) {
            script.exec(cx, executionScope);
        }
    }

    private static void defineReadOnlyProperty(ScriptableObject obj,
            String name, Object value) {
        ScriptableObject.putProperty(obj, name, value);
        obj.setAttributes(name, ScriptableObject.READONLY |
                ScriptableObject.PERMANENT);
    }

    private ModuleScript getModule(Context cx, String id, URI uri, URI base) {
        try {
            final ModuleScript moduleScript =
                    moduleScriptProvider.getModuleScript(cx, id, uri, base, paths);
            if (moduleScript == null) {
                throw ScriptRuntime.throwError(cx, nativeScope, "Module \""
                        + id + "\" not found.");
            }
            return moduleScript;
        }
        catch(RuntimeException e) {
            throw e;
        }
        catch(Exception e) {
            throw Context.throwAsScriptRuntimeEx(e);
        }
    }
}