// Shim of net.runelite.client.eventbus.EventBus (BSD-2, RuneLite).
//
// The real one wraps Guava's EventBus. This is a plain reflection dispatcher: register() collects
// @Subscribe methods by their single parameter type, post() dispatches to every subscriber of exactly
// that event class. Subclass parameters do not match, same as Guava's -- the plugin relies on that.
package net.runelite.client.eventbus;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EventBus
{
	private static final class Subscriber
	{
		final Object instance;
		final Method method;
		final int priority;

		Subscriber(Object instance, Method method, int priority)
		{
			this.instance = instance;
			this.method = method;
			this.priority = priority;
		}
	}

	private final Map<Class<?>, List<Subscriber>> subscribers = new HashMap<>();

	public void register(Object object)
	{
		for (Class<?> c = object.getClass(); c != null && c != Object.class; c = c.getSuperclass())
		{
			for (Method m : c.getDeclaredMethods())
			{
				Subscribe sub = m.getAnnotation(Subscribe.class);
				if (sub == null)
				{
					continue;
				}
				Class<?>[] params = m.getParameterTypes();
				if (params.length != 1)
				{
					continue;
				}
				m.setAccessible(true);
				subscribers.computeIfAbsent(params[0], k -> new ArrayList<>())
					.add(new Subscriber(object, m, sub.priority()));
			}
		}
	}

	public void unregister(Object object)
	{
		for (List<Subscriber> list : subscribers.values())
		{
			list.removeIf(s -> s.instance == object);
		}
	}

	/** Dispatch to every subscriber whose parameter type is exactly {@code event}'s class. */
	public void post(Object event)
	{
		if (event == null)
		{
			return;
		}
		List<Subscriber> list = subscribers.get(event.getClass());
		if (list == null)
		{
			return;
		}
		list.sort((a, b) -> Integer.compare(b.priority, a.priority));
		for (Subscriber s : list)
		{
			try
			{
				s.method.invoke(s.instance, event);
			}
			catch (Exception e)
			{
				System.err.println("event subscriber failed: " + s.method);
				e.printStackTrace();
			}
		}
	}

	/** Mostly for tests: who listens to {@code type}. */
	public List<Subscriber> listenersOf(Class<?> type)
	{
		return Collections.unmodifiableList(subscribers.getOrDefault(type, Collections.emptyList()));
	}
}
