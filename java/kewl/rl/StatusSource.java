// A hosted RuneLite-style plugin that wants a status line in kewl's control panel implements this;
// RlitePlugin.status() forwards to it when no auto-walk status is pending. Shim-only, not upstream.
package kewl.rl;

public interface StatusSource
{
	/** One short line for the panel; "" for nothing. Called on the frame thread. */
	String status();
}
