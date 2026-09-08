package kewl.plugins.autologin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * The direct field write, against a fabricated memory layout. No natives, no game.
 *
 * <p>{@link Fake} is a sparse byte map with the same three operations the DLL offers, and the same
 * refusal rules as {@code nSetLoginField} -- so a test can build the two situations that matter side
 * by side: the client's struct (username and password buffers 508 bytes apart with binary padding
 * between) and our own profile config.json in the JVM heap (the same two values a few bytes apart
 * with {@code ","password":"} between). Getting those two confused is a corrupted JVM heap, and it is
 * the reason the printable-gap rule exists.</p>
 */
public class FieldWriterTest
{
	private static final String USER = "someone@example.com";
	private static final String PASS = "Zz9!secret";

	/** A sparse memory: address -> byte, plus which addresses are writable. */
	static final class Fake implements FieldWriter.Memory
	{
		final Map<Long, Byte> bytes = new HashMap<>();
		final List<long[]> writableRanges = new ArrayList<>();
		final List<String> calls = new ArrayList<>();
		/** Forced refusal code for every write, or 0 for "behave like the native". */
		int refuseWith;

		void put(long at, String s)
		{
			byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
			for (int i = 0; i < b.length; i++) bytes.put(at + i, b[i]);
			bytes.put(at + b.length, (byte) 0);
		}

		/** A run of zeroes, i.e. an empty NUL-terminated buffer with room in it. */
		void zeroes(long at, int len)
		{
			for (int i = 0; i < len; i++) bytes.put(at + i, (byte) 0);
		}

		/** Binary padding: the shape of a struct, and what tells one from a document. */
		void padding(long at, int len)
		{
			for (int i = 0; i < len; i++) bytes.put(at + i, (byte) (0x80 + (i & 0x3F)));
		}

		void writable(long from, long to) { writableRanges.add(new long[] { from, to }); }

		private boolean isWritable(long at, int len)
		{
			for (long[] r : writableRanges) if (at >= r[0] && at + len <= r[1]) return true;
			return false;
		}

		@Override
		public long[] find(String needle)
		{
			List<Long> hits = new ArrayList<>();
			byte[] pat = needle.getBytes(StandardCharsets.ISO_8859_1);
			for (Long a : bytes.keySet())
			{
				boolean match = true;
				for (int i = 0; match && i < pat.length; i++)
				{
					Byte b = bytes.get(a + i);
					match = b != null && b == pat[i];
				}
				if (match) hits.add(a);
			}
			hits.sort(null);
			long[] out = new long[hits.size()];
			for (int i = 0; i < out.length; i++) out[i] = hits.get(i);
			return out;
		}

		@Override
		public String peek(long at, int len)
		{
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < len; i++)
			{
				Byte b = bytes.get(at + i);
				if (b == null) return "";                 // unreadable, exactly like the native
				sb.append(String.format("%02x", b & 0xFF));
			}
			return sb.toString();
		}

		@Override
		public int write(long at, String value, int cap)
		{
			calls.add("write@" + at);
			if (refuseWith != 0) return refuseWith;
			if (value.isEmpty() || value.length() + 1 > cap) return FieldWriter.REFUSED_ARGUMENT;
			if (peek(at, cap).isEmpty()) return FieldWriter.REFUSED_UNREADABLE;
			if (!isWritable(at, cap)) return FieldWriter.REFUSED_UNWRITABLE;
			int existing = 0;
			while (existing < cap && bytes.get(at + existing) != 0) existing++;
			if (existing == cap) return FieldWriter.REFUSED_CONTENT;
			if (existing != 0 && !value.equals(read(at, existing))) return FieldWriter.REFUSED_CONTENT;
			int zeros = 0;
			while (existing + zeros < cap && bytes.get(at + existing + zeros) == 0) zeros++;
			if (value.length() + 1 > existing + zeros) return FieldWriter.REFUSED_NO_ROOM;
			put(at, value);
			return FieldWriter.OK;
		}

		String read(long at, int len)
		{
			byte[] b = new byte[len];
			for (int i = 0; i < len; i++) b[i] = bytes.get(at + i);
			return new String(b, StandardCharsets.ISO_8859_1);
		}
	}

	/** The client's login struct: two fixed buffers PASSWORD_DELTA apart with padding between. */
	private static long clientStruct(Fake m, long base, String user, String passwordAlreadyThere)
	{
		m.put(base, user);
		m.padding(base + user.length() + 1, FieldWriter.PASSWORD_DELTA - user.length() - 1);
		if (passwordAlreadyThere == null) m.zeroes(base + FieldWriter.PASSWORD_DELTA, 64);
		else
		{
			m.put(base + FieldWriter.PASSWORD_DELTA, passwordAlreadyThere);
			m.zeroes(base + FieldWriter.PASSWORD_DELTA + passwordAlreadyThere.length() + 1,
					64 - passwordAlreadyThere.length() - 1);
		}
		m.writable(base, base + FieldWriter.PASSWORD_DELTA + 64);
		return base;
	}

	/** Our own profile config.json in the JVM heap: the same two values with PRINTABLE TEXT between. */
	private static void documentInTheHeap(Fake m, long base, String user, String pass)
	{
		m.put(base, user + "\",\"password\":\"" + pass + "\"}");
		m.writable(base, base + 256);
	}

	@Test
	public void theStructIsFoundAndBothFieldsAreWritten()
	{
		Fake m = new Fake();
		long base = 0x40000;
		clientStruct(m, base, USER, null);
		FieldWriter.Result r = FieldWriter.write(m, USER, PASS);
		assertTrue(r.status(), r.wrote());
		assertEquals("the username buffer is proven writable before the password goes anywhere",
				"write@" + base, m.calls.get(0));
		assertEquals("write@" + (base + FieldWriter.PASSWORD_DELTA), m.calls.get(1));
		assertEquals(PASS, m.read(base + FieldWriter.PASSWORD_DELTA, PASS.length()));
	}

	/**
	 * The pair that must never be written to. Its gap is printable text, which makes it a document --
	 * on 2026-09-06 that document was this client's own config.json, and a write into it corrupts the
	 * JVM's heap rather than the game's form.
	 */
	@Test
	public void aPairSeparatedByPrintableTextIsADocumentAndIsRefused()
	{
		Fake m = new Fake();
		documentInTheHeap(m, 0x90000, USER, PASS);
		FieldWriter.Result r = FieldWriter.write(m, USER, PASS);
		assertFalse(r.status(), r.wrote());
		assertTrue(r.status(), r.status().contains("ruled out as documents"));
		assertTrue("nothing was written at all", m.calls.isEmpty());
	}

	/** Both layouts present at once, which is what the live run actually found. */
	@Test
	public void theDocumentIsDiscardedAndTheStructIsStillFound()
	{
		Fake m = new Fake();
		long base = 0x40000;
		clientStruct(m, base, USER, null);
		documentInTheHeap(m, 0x90000, USER, PASS);
		FieldWriter.Result r = FieldWriter.write(m, USER, PASS);
		assertTrue(r.status(), r.wrote());
		assertEquals("write@" + base, m.calls.get(0));
	}

	@Test
	public void twoEquallyGoodCandidatesWriteNothing()
	{
		Fake m = new Fake();
		clientStruct(m, 0x40000, USER, null);
		clientStruct(m, 0x80000, USER, null);
		FieldWriter.Result r = FieldWriter.write(m, USER, PASS);
		assertFalse(r.status(), r.wrote());
		assertTrue(r.status(), r.status().contains("2 candidate field pair(s)"));
		assertTrue("a delta that matches twice is a delta that matches by chance", m.calls.isEmpty());
	}

	/** The buffer at the delta must be EMPTY -- that is the "Please enter your password" state. */
	@Test
	public void aPasswordBufferWithSomethingInItIsNotWrittenOver()
	{
		Fake m = new Fake();
		clientStruct(m, 0x40000, USER, "somethingelse");
		FieldWriter.Result r = FieldWriter.write(m, USER, PASS);
		assertFalse(r.status(), r.wrote());
		assertTrue(r.status(), r.status().contains("with a non-empty buffer"));
		assertTrue(m.calls.isEmpty());
	}

	@Test
	public void aUsernameNowhereInMemoryFallsBackToTyping()
	{
		FieldWriter.Result r = FieldWriter.write(new Fake(), USER, PASS);
		assertFalse(r.wrote());
		assertTrue(r.status(), r.status().contains("not holding the username"));
	}

	@Test
	public void anAddressTheNativeRefusesFallsBackToTypingWithTheReason()
	{
		Fake m = new Fake();
		clientStruct(m, 0x40000, USER, null);
		m.refuseWith = FieldWriter.REFUSED_UNWRITABLE;
		FieldWriter.Result r = FieldWriter.write(m, USER, PASS);
		assertFalse(r.wrote());
		assertTrue(r.status(), r.status().contains("not writable"));
		assertTrue(r.status(), r.status().endsWith("typing instead"));
	}

	/** A read-only page: the native refuses rather than calling VirtualProtect on someone else's memory. */
	@Test
	public void aReadOnlyStructIsRefusedByTheWriteItself()
	{
		Fake m = new Fake();
		long base = 0x40000;
		m.put(base, USER);
		m.padding(base + USER.length() + 1, FieldWriter.PASSWORD_DELTA - USER.length() - 1);
		m.zeroes(base + FieldWriter.PASSWORD_DELTA, 64);
		// deliberately NOT marked writable
		FieldWriter.Result r = FieldWriter.write(m, USER, PASS);
		assertFalse(r.wrote());
		assertTrue(r.status(), r.status().contains("not writable"));
	}

	@Test
	public void credentialsWithNothingInThemNeverTouchMemory()
	{
		Fake m = new Fake();
		assertFalse(FieldWriter.write(m, (Credentials) null).wrote());
		assertFalse(FieldWriter.write(m, Credentials.resolve("", "", java.nio.file.Path.of("."))).wrote());
		assertTrue(m.calls.isEmpty());
	}

	@Test
	public void aUsernameTooShortToSearchForIsNotSearchedFor()
	{
		Fake m = new Fake();
		FieldWriter.Result r = FieldWriter.write(m, "ab", PASS);
		assertFalse(r.wrote());
		assertTrue(r.status(), r.status().contains("too short"));
	}

	/**
	 * The rule this whole class is built around. Every status line, on every path, is counts and
	 * refusal words -- no value, no length, and no substring of either.
	 */
	@Test
	public void noStatusLineOnAnyPathContainsACredential()
	{
		StringBuilder all = new StringBuilder();
		Fake ok = new Fake();
		clientStruct(ok, 0x40000, USER, null);
		all.append(FieldWriter.write(ok, USER, PASS).status()).append('\n');

		Fake doc = new Fake();
		documentInTheHeap(doc, 0x90000, USER, PASS);
		all.append(FieldWriter.write(doc, USER, PASS).status()).append('\n');

		Fake two = new Fake();
		clientStruct(two, 0x40000, USER, null);
		clientStruct(two, 0x80000, USER, null);
		all.append(FieldWriter.write(two, USER, PASS).status()).append('\n');

		Fake busy = new Fake();
		clientStruct(busy, 0x40000, USER, PASS);
		all.append(FieldWriter.write(busy, USER, PASS).status()).append('\n');

		Fake refused = new Fake();
		clientStruct(refused, 0x40000, USER, null);
		for (int code = 0; code >= -7; code--)
		{
			refused.refuseWith = code == 0 ? -1 : code;
			all.append(FieldWriter.write(refused, USER, PASS).status()).append('\n');
			all.append(FieldWriter.refusal(code)).append('\n');
		}
		all.append(FieldWriter.write(new Fake(), USER, PASS).status()).append('\n');
		all.append(FieldWriter.write(new Fake(), "ab", PASS).status()).append('\n');
		all.append(FieldWriter.write(new Fake(), USER, "x".repeat(FieldWriter.CAP + 1)).status()).append('\n');
		all.append(FieldWriter.write(new Fake(), (Credentials) null).status()).append('\n');

		String s = all.toString();
		assertFalse(s, s.contains(PASS));
		assertFalse(s, s.contains("secret"));
		assertFalse(s, s.contains("Zz9"));
		assertFalse("nor the username", s.contains(USER));
		assertFalse("nor its local part", s.contains("someone"));
		assertFalse("nor the domain", s.contains("example.com"));
	}
}
