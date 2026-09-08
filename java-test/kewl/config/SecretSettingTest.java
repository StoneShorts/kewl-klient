package kewl.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import kewl.Plugin;

/**
 * The secret flavour of a TEXT setting (Config.secret): the same setting in every respect the plugin,
 * the persistence layer and the edit paths can see, and a masked one in every respect a renderer or a
 * log can. These pin both halves -- a "secret" that behaved differently under set/reset would break
 * the AutoLogin panel, and one whose displayText leaked would break the credentials rule.
 */
public class SecretSettingTest
{
	private static final class P extends Plugin
	{
		@Override public String name() { return "SecretSettingTest"; }
	}

	@Test
	public void secretIsATextSettingWithTheFlag()
	{
		P p = new P();
		Setting s = p.config.secret("pw", "Password", "desc", "");
		assertEquals(Setting.Kind.TEXT, s.kind());
		assertTrue(s.secret());
		assertEquals("", s.asText());
		assertEquals("", s.defaultValue());
		assertEquals("declared in order, like every other setting", s, p.config.get("pw"));

		Setting t = p.config.text("user", "Username", "desc", "");
		assertEquals(Setting.Kind.TEXT, t.kind());
		assertFalse("an ordinary text setting is not secret", t.secret());
	}

	@Test
	public void setResetAndListenersBehaveLikeText()
	{
		P p = new P();
		Setting s = p.config.secret("pw", "Password", "", "");
		AtomicInteger fired = new AtomicInteger();
		s.onChange(fired::incrementAndGet);
		long before = Setting.REVISION;

		s.set("hunter2-not-a-real-password");
		assertEquals("hunter2-not-a-real-password", s.asText());
		assertEquals("hunter2-not-a-real-password", p.config.text("pw"));
		assertEquals(1, fired.get());
		assertTrue(Setting.REVISION != before);

		s.reset();
		assertEquals("", s.asText());
		assertEquals(2, fired.get());
	}

	@Test
	public void displayTextMasksASecretAndNothingElse()
	{
		P p = new P();
		Setting s = p.config.secret("pw", "Password", "", "");
		assertEquals("(empty)", s.displayText());
		s.set("hunter2-not-a-real-password");
		String shown = s.displayText();
		assertEquals(Setting.SECRET_MASK, shown);
		assertFalse(shown.contains("hunter2"));
		// Fixed width: the mask must not be as long as the value, because a length is still a fact
		// about the password.
		s.set("x");
		assertEquals(Setting.SECRET_MASK, s.displayText());

		Setting t = p.config.text("user", "Username", "", "");
		t.set("tester@example.org");
		assertEquals("a plain text setting shows its value", "tester@example.org", t.displayText());
	}
}
