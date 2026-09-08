// The config proxy must answer every @ConfigItem with the interface's OWN declared default.
//
// This is the regression test for a bug that never failed a compile and never threw a stack trace:
// lookupDefault used findStatic for what are default (non-static) interface methods, every lookup
// failed, and every default silently degraded to a type default -- a @Range(min=1,max=30) item ended
// up with a default of 0, which JSlider then refused to construct, which killed the ENTIRE control
// panel. The test reads two ends of the range of shapes: an in-range int default, and an int default
// far outside any slider's comfort (currency threshold, 100000, which has no @Range at all).
package net.runelite.client.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import shortestpath.ShortestPathConfig;

public class ConfigDefaultsTest
{
	@Test
	public void inRangeIntDefaultComesFromTheInterface()
	{
		assertEquals(5, (int) declaredDefault("calculationCutoff"));
	}

	@Test
	public void unRangedIntDefaultIsNotCapped()
	{
		assertEquals(100000, (int) declaredDefault("currencyThreshold"));
	}

	@Test
	public void declaringTheFullConfigSurvivesPanelConstruction()
	{
		// The crash was: declare() -> kewl Setting with an out-of-range default -> JSlider constructor
		// throws in the old Swing panel -> KewlKlient's catch eats it -> no control panel at all. That
		// panel is gone (deleted 2026-09-07), so this asserts the invariant its constructor used to
		// enforce by accident: every declared setting is one a panel can draw. See
		// ConfigPanelInvariants for why that is the stronger check.
		kewl.config.Config kewlConfig = new kewl.config.Config();
		ConfigManager manager = new ConfigManager(null, kewlConfig);
		ShortestPathConfig config = manager.getConfig(ShortestPathConfig.class, kewlConfig);
		assertEquals(5, config.calculationCutoff());

		ConfigPanelInvariants.assertEverySettingIsRenderable(kewlConfig, "ShortestPathConfig");
		// The count is DERIVED, not a magic number: hard-coding it meant every new @ConfigItem
		// (autoWalkClickDelay, added 2026-09-06 for the ground-click walker) failed this test for the
		// wrong reason. What must hold is the invariant -- every visible @ConfigItem becomes exactly
		// one setting.
		int expected = visibleConfigItems();
		assertTrue("no @ConfigItems found -- reflection broke, not the config", expected > 0);
		assertEquals(expected, kewlConfig.all().size());
	}

	/** @ConfigItem methods the panel is expected to show: all of them except the hidden ones. */
	private static int visibleConfigItems()
	{
		int n = 0;
		for (java.lang.reflect.Method m : ShortestPathConfig.class.getMethods())
		{
			ConfigItem item = m.getAnnotation(ConfigItem.class);
			if (item != null && !item.hidden())
			{
				n++;
			}
		}
		return n;
	}

	private static Object declaredDefault(String method)
	{
		for (java.lang.reflect.Method m : ShortestPathConfig.class.getMethods())
		{
			if (m.getName().equals(method))
			{
				return ConfigManager.lookupDefault(ShortestPathConfig.class, m);
			}
		}
		throw new AssertionError("no config method named " + method);
	}
}
