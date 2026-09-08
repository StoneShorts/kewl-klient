// Shim of net.runelite.client.config.ConfigManager (BSD-2, RuneLite).
//
// The real one persists to a properties file and hands back injected config interfaces. Here a config
// interface becomes a java.lang.reflect.Proxy over kewl's Setting system: walking the interface's
// @ConfigItem methods declares the matching kewl settings, so the control panel grows real controls
// (checkbox, slider, colour swatch, enum drop-down) that the plugin never wrote.
//
// Defaults come from the interface's default methods. Changes flow both ways: the panel edits the
// Setting, and Setting.onChange posts a ConfigChanged so the plugin reacts like it would in RuneLite.
package net.runelite.client.config;

import java.awt.Color;
import java.awt.event.KeyEvent;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.EventBus;
import kewl.config.Setting;

public class ConfigManager
{
	private final EventBus eventBus;
	/** The kewl settings used when a config interface is requested without one, e.g. via a plugin's
	 *  own @Provides method that only passes the ConfigManager. */
	private final kewl.config.Config defaultKewlConfig;
	private final Map<Class<?>, Object> configs = new ConcurrentHashMap<>();

	public ConfigManager(EventBus eventBus, kewl.config.Config defaultKewlConfig)
	{
		this.eventBus = eventBus;
		this.defaultKewlConfig = defaultKewlConfig;
	}

	/**
	 * Build (or return the cached) proxy for a config interface. {@code kewlConfig} is the settings
	 * object of the plugin adapter this config belongs to; declared settings appear in the control
	 * panel under that plugin.
	 */
	@SuppressWarnings("unchecked")
	public <T extends Config> T getConfig(Class<T> iface, kewl.config.Config kewlConfig)
	{
		return (T) configs.computeIfAbsent(iface, i -> buildProxy(i, kewlConfig));
	}

	@SuppressWarnings("unchecked")
	public <T extends Config> T getConfig(Class<T> iface)
	{
		return (T) configs.computeIfAbsent(iface, i -> buildProxy(i, defaultKewlConfig));
	}

	private Object buildProxy(Class<?> iface, kewl.config.Config kewlConfig)
	{
		// Map @ConfigItem keyName -> kewl Setting, declared in interface order so the panel reads
		// top to bottom the way the plugin's source does.
		Map<String, Setting> byKey = new ConcurrentHashMap<>();
		for (Method m : iface.getMethods())
		{
			ConfigItem item = m.getAnnotation(ConfigItem.class);
			if (item == null)
			{
				continue; // @ConfigSection methods carry no value
			}
			if (item.hidden())
			{
				continue; // the plugin reads it, but the panel must not show it; the proxy answers
						  // it from the interface's own default
			}
			if (kewlConfig == null || kewlConfig.get(item.keyName()) != null)
			{
				continue; // already declared
			}
			Object def;
			try
			{
				def = defaultOf(iface, m);
			}
			catch (Throwable t)
			{
				def = null;
			}
			declare(kewlConfig, m, item, def);
			Setting s = kewlConfig.get(item.keyName());
			if (s != null)
			{
				byKey.put(item.keyName(), s);
				ConfigGroup group = iface.getAnnotation(ConfigGroup.class);
				s.onChange(() -> postChanged(group, item, s));
			}
		}

		Class<?> proxyClass = Proxy.getProxyClass(iface.getClassLoader(), iface);
		try
		{
			Object proxy = proxyClass
				.getConstructor(InvocationHandler.class)
				.newInstance(new Handler(iface, byKey));
			return proxy;
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException("cannot proxy config " + iface, e);
		}
	}

	private void postChanged(ConfigGroup group, ConfigItem item, Setting s)
	{
		if (eventBus == null || group == null)
		{
			return;
		}
		ConfigChanged ev = new ConfigChanged();
		ev.setGroup(group.value());
		ev.setKey(item.keyName());
		eventBus.post(ev);
	}

	/** Translate one config method into the kewl setting kind the panel knows how to draw. */
	private void declare(kewl.config.Config kewlConfig, Method m, ConfigItem item, Object def)
	{
		Class<?> type = m.getReturnType();
		String key = item.keyName();
		String label = item.name();
		String desc = item.description();
		Range range = m.getAnnotation(Range.class);

		if (type == boolean.class)
		{
			kewlConfig.bool(key, label, desc, def instanceof Boolean b && b);
		}
		else if (type == int.class)
		{
			// The declared default has to land inside the declared range or the panel's JSlider
			// constructor throws and the whole control panel dies with it. Without a @Range there is
			// no slider-friendly window, so give the full positive int span; the value is still
			// readable and the panel still shows it.
			int min = range != null ? range.min() : 0;
			int max = range != null ? range.max() : Integer.MAX_VALUE;
			int value = def instanceof Integer i ? i : 0;
			kewlConfig.number(key, label, desc, Math.max(min, Math.min(max, value)), min, max);
		}
		else if (type == double.class)
		{
			// RuneLite's NPC Indicators declares `double borderWidth()`; kewl has no fractional setting
			// kind, so a double becomes the same INT slider an int gets (rounded default, @Range
			// honoured). Whole-pixel widths are all the Java2D BasicStroke here ever needs, and a
			// text box for "2.0" was the alternative -- unusable from the ImGui strip.
			int min = range != null ? range.min() : 0;
			int max = range != null ? range.max() : Integer.MAX_VALUE;
			int value = def instanceof Double d ? (int) Math.round(d) : 0;
			kewlConfig.number(key, label, desc, Math.max(min, Math.min(max, value)), min, max);
		}
		else if (type == Color.class)
		{
			kewlConfig.colour(key, label, desc, def instanceof Color c ? c : Color.WHITE);
		}
		else if (type == Keybind.class)
		{
			// kewl hotkeys are F1-F8; store the F-index (0 = not set) in an INT setting.
			int fIndex = 0;
			if (def instanceof Keybind k && k.getKeyCode() >= KeyEvent.VK_F1)
			{
				fIndex = k.getKeyCode() - KeyEvent.VK_F1 + 1;
			}
			kewlConfig.number(key, label, desc, fIndex, 0, 8);
		}
		else if (type.isEnum())
		{
			@SuppressWarnings({"unchecked", "rawtypes"})
			Enum<?> e = def instanceof Enum ? (Enum<?>) def : null;
			if (e == null)
			{
				@SuppressWarnings({"unchecked", "rawtypes"})
				Class<? extends Enum> ec = (Class<? extends Enum>) type;
				e = ec.getEnumConstants()[0];
			}
			kewlConfig.enumeration(key, label, desc, e);
		}
		else
		{
			kewlConfig.text(key, label, desc, def == null ? "" : String.valueOf(def));
		}
	}

	/** Invoke the interface's default method to read a plugin's declared default value. */
	private static Object defaultOf(Class<?> iface, Method m)
	{
		return lookupDefault(iface, m);
	}

	/**
	 * Read a default method's value via MethodHandles. Falls back to a type default when the lookup
	 * cannot reach it (some JVMs restrict private interface lookups).
	 *
	 * <p>findSpecial rather than findStatic: config defaults are interface DEFAULT methods, not static
	 * ones, so findStatic throws NoSuchMethodException for every single item and every default in the
	 * panel silently degraded to a type default (which is how a @Range(min=1,max=30) item ended up with
	 * a default of 0). findSpecial invokes the interface body directly, non-virtually, on a receiver
	 * that is only there to satisfy the type -- a throwaway proxy whose handler would loop back here if
	 * the body ever touched {@code this}, which config defaults do not.</p>
	 */
	static Object lookupDefault(Class<?> iface, Method m)
	{
		try
		{
			java.lang.invoke.MethodHandles.Lookup lookup =
				java.lang.invoke.MethodHandles.privateLookupIn(iface, java.lang.invoke.MethodHandles.lookup());
			Object receiver = java.lang.reflect.Proxy.newProxyInstance(iface.getClassLoader(),
				new Class<?>[]{iface},
				(p, method, args) ->
				{
					throw new IllegalStateException("config default in " + iface.getSimpleName()
						+ "." + m.getName() + " read its receiver");
				});
			return lookup.findSpecial(iface, m.getName(),
				java.lang.invoke.MethodType.methodType(m.getReturnType(), m.getParameterTypes()), iface)
				.bindTo(receiver).invokeWithArguments();
		}
		catch (Throwable t)
		{
			return defaultValueForType(m.getReturnType());
		}
	}

	private static Object defaultValueForType(Class<?> type)
	{
		if (type == boolean.class) return false;
		if (type == int.class) return 0;
		if (type == double.class) return 0d;
		if (type == Color.class) return Color.WHITE;
		if (type == Keybind.class) return Keybind.NOT_SET;
		if (type == String.class) return "";
		return null;
	}

	private final class Handler implements InvocationHandler
	{
		private final Class<?> iface;
		private final Map<String, Setting> byKey;

		Handler(Class<?> iface, Map<String, Setting> byKey)
		{
			this.iface = iface;
			this.byKey = byKey;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args)
		{
			String name = method.getName();
			ConfigItem item = method.getAnnotation(ConfigItem.class);
			if (item != null)
			{
				Setting s = byKey.get(item.keyName());
				if (s != null)
				{
					return read(s, method.getReturnType());
				}
				// No kewlConfig was supplied (tests, or a config read before registration): use the
				// interface's own default.
				Object def = lookupDefault(iface, method);
				if (def != null || method.getReturnType() == String.class)
				{
					return def;
				}
				return defaultValueForType(method.getReturnType());
			}
			if (name.equals("toString"))
			{
				return iface.getSimpleName() + "(shim proxy)";
			}
			if (name.equals("equals"))
			{
				return proxy == args[0];
			}
			if (name.equals("hashCode"))
			{
				return System.identityHashCode(proxy);
			}
			return defaultValueForType(method.getReturnType());
		}

		private Object read(Setting s, Class<?> type)
		{
			if (type == boolean.class) return s.asBool();
			if (type == int.class) return s.asInt();
			if (type == double.class) return (double) s.asInt(); // stored as an INT slider, see declare()
			if (type == Color.class) return s.asColor();
			if (type == String.class) return s.asText();
			if (type == Keybind.class)
			{
				int f = s.asInt();
				return f >= 1 && f <= 8 ? new Keybind(KeyEvent.VK_F1 + f - 1) : Keybind.NOT_SET;
			}
			if (type.isEnum())
			{
				Object v = s.value();
				for (Object c : type.getEnumConstants())
				{
					if (c == v)
					{
						return c;
					}
				}
				return type.getEnumConstants()[0];
			}
			return defaultValueForType(type);
		}
	}
}
