// Builds a RuneLite-style plugin's object graph without Guice.
//
// RuneLite wires plugins through Guice: @Inject constructors and fields, plus a @Provides method for
// the plugin's config interface. The ported plugins keep those annotations untouched, and this is what
// understands them: it instantiates the plugin and every @Inject'd helper (overlays), resolving each
// parameter from:
//
//   1. the shared bindings (Client, EventBus, OverlayManager, KeyManager, SpriteManager, ConfigManager)
//   2. a @Provides method on the plugin (the config factory)
//   3. an interface annotated @ConfigGroup (ConfigManager builds the proxy)
//   4. otherwise, a recursive constructor call -- which is how overlays get built
//
// and then registers everything with @Subscribe methods on the event bus.
package kewl.rl;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;

import com.google.inject.Inject;
import com.google.inject.Provides;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.plugins.Plugin;

public final class Injector
{
	private final Map<Class<?>, Object> bindings = new IdentityHashMap<>();
	private final EventBus eventBus;
	private final ConfigManager configManager;
	private final kewl.config.Config kewlConfig;

	public Injector(EventBus eventBus, ConfigManager configManager, kewl.config.Config kewlConfig)
	{
		this.eventBus = eventBus;
		this.configManager = configManager;
		this.kewlConfig = kewlConfig;
	}

	public <T> void bind(Class<T> type, T instance)
	{
		bindings.put(type, instance);
	}

	/** Instantiate {@code pluginClass}, inject everything reachable from it, and register subscribers. */
	public <T extends Plugin> T build(Class<T> pluginClass)
	{
		T plugin = construct(pluginClass);
		return build(plugin);
	}

	/** Inject an already-constructed plugin (built by the caller's factory), and register it. */
	public <T extends Plugin> T build(T plugin)
	{
		bindings.put(plugin.getClass(), plugin);
		injectInto(plugin);
		eventBus.register(plugin);
		return plugin;
	}

	private void injectInto(Object target)
	{
		for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass())
		{
			for (Field f : c.getDeclaredFields())
			{
				if (!isInject(f.getAnnotations()))
				{
					continue;
				}
				f.setAccessible(true);
				try
				{
					f.set(target, resolve(f.getType(), target));
				}
				catch (ReflectiveOperationException | RuntimeException e)
				{
					throw new IllegalStateException("cannot inject " + f + " into " + target.getClass(), e);
				}
			}
		}
	}

	private Object resolve(Class<?> type, Object plugin)
	{
		Object bound = bindings.get(type);
		if (bound != null)
		{
			return bound;
		}

		// A @Provides method on the plugin is the config factory (and anything else it provides).
		Object provided = fromProvides(type, plugin);
		if (provided != null)
		{
			return provided;
		}

		// A config interface: build the proxy over kewl's settings.
		ConfigGroup group = type.getAnnotation(ConfigGroup.class);
		if (group != null && Config.class.isAssignableFrom(type))
		{
			@SuppressWarnings("unchecked")
			Class<? extends Config> cfg = (Class<? extends Config>) type;
			Object proxy = configManager.getConfig(cfg, kewlConfig);
			bindings.put(type, proxy);
			return proxy;
		}

		// Anything else with an @Inject constructor (overlays) gets built the same way.
		Object built = construct(type);
		bindings.put(type, built);
		injectInto(built);
		eventBus.register(built);
		return built;
	}

	private Object fromProvides(Class<?> type, Object plugin)
	{
		Plugin p = plugin instanceof Plugin ? (Plugin) plugin : null;
		if (p == null)
		{
			return null;
		}
		for (Class<?> c = p.getClass(); c != null && c != Object.class; c = c.getSuperclass())
		{
			for (Method m : c.getDeclaredMethods())
			{
				if (!m.isAnnotationPresent(Provides.class) || m.getReturnType() != type)
				{
					continue;
				}
				m.setAccessible(true);
				Class<?>[] params = m.getParameterTypes();
				Object[] args = new Object[params.length];
				for (int i = 0; i < params.length; i++)
				{
					args[i] = resolve(params[i], plugin);
				}
				try
				{
					Object result = m.invoke(p, args);
					bindings.put(type, result);
					return result;
				}
				catch (ReflectiveOperationException e)
				{
					throw new IllegalStateException("@Provides " + m + " failed", e);
				}
			}
		}
		return null;
	}

	/** Hub plugins use javax.inject.Inject; our shim classes use com.google.inject.Inject. Take both. */
	private static boolean isInject(java.lang.annotation.Annotation[] annotations)
	{
		for (java.lang.annotation.Annotation a : annotations)
		{
			if (a.annotationType().getSimpleName().equals("Inject"))
			{
				return true;
			}
		}
		return false;
	}

	private <T> T construct(Class<T> type)
	{
		Constructor<T> chosen = null;
		for (Constructor<T> c : (Constructor<T>[]) type.getDeclaredConstructors())
		{
			if (isInject(c.getAnnotations()))
			{
				chosen = c;
				break;
			}
			if (c.getParameterCount() == 0)
			{
				chosen = c;
			}
		}
		if (chosen == null)
		{
			throw new IllegalStateException("no usable constructor for " + type);
		}
		chosen.setAccessible(true);
		Class<?>[] params = chosen.getParameterTypes();
		Object[] args = new Object[params.length];
		for (int i = 0; i < params.length; i++)
		{
			args[i] = resolve(params[i], null);
		}
		try
		{
			return chosen.newInstance(args);
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException("cannot construct " + type, e);
		}
	}
}
