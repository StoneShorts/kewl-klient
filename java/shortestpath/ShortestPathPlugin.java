package shortestpath;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.ScriptID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.PostClientTick;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.events.WorldChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.JagexColors;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.PathStep;
import shortestpath.pathfinder.Pathfinder;
import shortestpath.pathfinder.PathfinderConfig;
import shortestpath.transport.Transport;
import shortestpath.transport.TransportType;

@SuppressWarnings("SameParameterValue")
@PluginDescriptor(name = "Shortest Path", description = "Draws the shortest path to a chosen destination on the map<br>"
	+
	"Right click on the world map or shift right click a tile to use", tags = {"pathfinder", "map", "waypoint",
	"navigation"})
public class ShortestPathPlugin extends Plugin
{
	protected static final String CONFIG_GROUP = "shortestpath";

	// POH (Player Owned House) bounds for detecting when path goes through POH
	// Note: POH_MIN_X is 1856 to exclude the Daddy's Home miniquest area
	private static final int POH_MIN_X = 1856;
	private static final int POH_MAX_X = 2047;
	private static final int POH_MIN_Y = 5696;
	private static final int POH_MAX_Y = 5767;
	private static final String PLUGIN_MESSAGE_PATH = "path";
	private static final String PLUGIN_MESSAGE_CLEAR = "clear";
	private static final String PLUGIN_MESSAGE_START = "start";
	private static final String PLUGIN_MESSAGE_TARGET = "target";
	private static final String PLUGIN_MESSAGE_CONFIG_OVERRIDE = "config";
	private static final String PLUGIN_MESSAGE_TRANSPORTS = "transports";
	private static final String CLEAR = "Clear";
	private static final String PATH = ColorUtil.wrapWithColorTag("Path", JagexColors.MENU_TARGET);
	private static final String SET = "Set";
	private static final String FIND_CLOSEST = "Find closest";
	private static final String FLASH_ICONS = "Flash icons";
	private static final String START = ColorUtil.wrapWithColorTag("Start", JagexColors.MENU_TARGET);
	private static final String TARGET = ColorUtil.wrapWithColorTag("Target", JagexColors.MENU_TARGET);
	private static final BufferedImage MARKER_IMAGE = ImageUtil.loadImageResource(ShortestPathPlugin.class, "/marker.png");
	private static final Pattern TRANSPORT_OPTIONS_REGEX = Pattern.compile("^(avoidWilderness|includeBankPath|currencyThreshold|use\\w+|cost\\w+)$");
	private static final Map<String, Object> configOverride = new HashMap<>(50);
	private static final Pattern SPIRIT_TREE_LABEL_PATTERN_MENU = Pattern.compile("<col=735a28>(.+)</col>: (<col=5f5f5f>)?(.+)");
	private static final Pattern SPIRIT_TREE_LABEL_PATTERN_MENU_NEW = Pattern.compile("<col=ffffff>(.+)</col>: (<col=5f5f5f>)?(.+)");
	private final List<PendingTask> pendingTasks = new ArrayList<>(3);
	private final Object pathfinderMutex = new Object();
	boolean drawCollisionMap;
	boolean drawMap;
	boolean drawMinimap;
	boolean drawTiles;
	boolean drawTransports;
	boolean showTransportInfo;
	boolean showBankPickupInfo;
	Color colourCollisionMap;
	Color colourPath;
	Color colourPathCalculating;
	Color colourPathUnreachable;
	Color colourText;
	Color colourTransports;
	int tileCounterStep;
	int unreachableTargetDistance;
	String unreachableText;
	TileCounter showTileCounter;
	TileStyle pathStyle;
	@Inject
	private Client client;
	@Getter
	@Inject
	private ClientThread clientThread;
	@Inject
	private ShortestPathConfig config;
	@Inject
	private EventBus eventBus;
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private PathTileOverlay pathOverlay;
	@Inject
	private PathMinimapOverlay pathMinimapOverlay;
	@Inject
	private PathMapOverlay pathMapOverlay;
	@Inject
	private PathMapTooltipOverlay pathMapTooltipOverlay;
	@Inject
	private DebugOverlayPanel debugOverlayPanel;
	@Inject
	private SpriteManager spriteManager;
	@Inject
	private WorldMapPointManager worldMapPointManager;
	@Inject
	private KeyManager keyManager;
	private Point lastMenuOpenedPoint;
	private WorldMapPoint marker;
	private int lastLocation = WorldPointUtil.packWorldPoint(0, 0, 0);
	private Shape minimapClipFixed;
	private Shape minimapClipResizeable;
	private BufferedImage minimapSpriteFixed;
	private BufferedImage minimapSpriteResizeable;
	private Rectangle minimapRectangle = new Rectangle();
	private GameState lastGameState = null;
	private GameState lastLastGameState = null;
	private ExecutorService pathfindingExecutor = Executors.newSingleThreadExecutor();
	private Future<?> pathfinderFuture;
	@Getter
	private Pathfinder pathfinder;
	@Getter
	private PathfinderConfig pathfinderConfig;
	@Getter
	private boolean startPointSet = false;
	private final KeyListener clearPathKeylistener = new KeyListener()
	{
		@Override
		public void keyTyped(KeyEvent e)
		{
		}

		@Override
		public void keyPressed(KeyEvent e)
		{
			if (config.clearPathHotkey().matches(e))
			{
				setTarget(WorldPointUtil.UNDEFINED);
			}
		}

		@Override
		public void keyReleased(KeyEvent e)
		{
		}
	};
	private boolean fairyRingPanelOpen = false;

	/**
	 * Checks if the given coordinates are inside the POH (Player Owned House) area.
	 *
	 * @param x The world X coordinate
	 * @param y The world Y coordinate
	 * @return true if inside POH, false otherwise
	 */
	public static boolean isInsidePoh(int x, int y)
	{
		return x >= POH_MIN_X && x <= POH_MAX_X && y >= POH_MIN_Y && y <= POH_MAX_Y;
	}

	public static boolean override(String configOverrideKey, boolean defaultValue)
	{
		if (!configOverride.isEmpty())
		{
			Object value = configOverride.get(configOverrideKey);
			if (value instanceof Boolean)
			{
				return (boolean) value;
			}
		}
		return defaultValue;
	}

	/**
	 * Override for TransportType enabled state using the config key name stored in the enum.
	 */
	public static boolean override(TransportType type, boolean defaultValue)
	{
		String key = type.getEnabledKey();
		return key != null ? override(key, defaultValue) : defaultValue;
	}

	/**
	 * Override for TransportType cost threshold using the config key name stored in the enum.
	 */
	public static int override(TransportType type, int defaultValue)
	{
		String key = type.getCostKey();
		return key != null ? override(key, defaultValue) : defaultValue;
	}

	public static int override(String configOverrideKey, int defaultValue)
	{
		if (!configOverride.isEmpty())
		{
			Object value = configOverride.get(configOverrideKey);
			if (value instanceof Integer)
			{
				return (int) value;
			}
		}
		return defaultValue;
	}

	public static TeleportationItem override(String configOverrideKey, TeleportationItem defaultValue)
	{
		if (!configOverride.isEmpty())
		{
			Object value = configOverride.get(configOverrideKey);
			if (value instanceof String)
			{
				TeleportationItem teleportationItem = TeleportationItem.fromType((String) value);
				if (teleportationItem != null)
				{
					return teleportationItem;
				}
			}
		}
		return defaultValue;
	}

	public static JewelleryBoxTier override(String configOverrideKey, JewelleryBoxTier defaultValue)
	{
		if (!configOverride.isEmpty())
		{
			Object value = configOverride.get(configOverrideKey);
			if (value instanceof String)
			{
				JewelleryBoxTier tier = JewelleryBoxTier.fromType((String) value);
				if (tier != null)
				{
					return tier;
				}
			}
		}
		return defaultValue;
	}

	@Provides
	public ShortestPathConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ShortestPathConfig.class);
	}

	/**
	 * Kewl addition, not upstream: the pace {@code kewl.rl.AutoWalk} clicks at, in game ticks. Read
	 * live rather than from the cacheConfigValues snapshot so a user dragging the slider while a walk
	 * is running sees it take effect on the next click. Zero before injection has run; the driver
	 * floors it.
	 */
	public int getAutoWalkClickDelay()
	{
		return config == null ? 0 : config.autoWalkClickDelay();
	}

	@Override
	protected void startUp()
	{
		cacheConfigValues();

		pathfinderConfig = new PathfinderConfig(client, config);
		if (GameState.LOGGED_IN.equals(client.getGameState()))
		{
			clientThread.invokeLater(pathfinderConfig::refresh);
		}

		overlayManager.add(pathOverlay);
		overlayManager.add(pathMinimapOverlay);
		overlayManager.add(pathMapOverlay);
		overlayManager.add(pathMapTooltipOverlay);

		if (config.drawDebugPanel())
		{
			overlayManager.add(debugOverlayPanel);
		}

		keyManager.registerKeyListener(clearPathKeylistener);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(pathOverlay);
		overlayManager.remove(pathMinimapOverlay);
		overlayManager.remove(pathMapOverlay);
		overlayManager.remove(pathMapTooltipOverlay);
		overlayManager.remove(debugOverlayPanel);

		if (pathfindingExecutor != null)
		{
			pathfindingExecutor.shutdownNow();
			pathfindingExecutor = null;
		}

		keyManager.unregisterKeyListener(clearPathKeylistener);
	}

	public void restartPathfinding(int start, Set<Integer> ends, boolean canReviveFiltered)
	{
		synchronized (pathfinderMutex)
		{
			if (pathfinder != null)
			{
				pathfinder.cancel();
				pathfinderFuture.cancel(true);
			}

			if (pathfindingExecutor == null)
			{
				ThreadFactory shortestPathNaming = new ThreadFactoryBuilder().setNameFormat("shortest-path-%d").build();
				pathfindingExecutor = Executors.newSingleThreadExecutor(shortestPathNaming);
			}
		}

		getClientThread().invokeLater(() ->
		{
			pathfinderConfig.refresh();
			pathfinderConfig.filterLocations(ends, canReviveFiltered);
			synchronized (pathfinderMutex)
			{
				if (ends.isEmpty())
				{
					System.out.println("[shortestpath] every target was filtered out -- nothing to path to");
					setTarget(WorldPointUtil.UNDEFINED);
				}
				else
				{
					pathfinder = new Pathfinder(pathfinderConfig, start, ends, this::postPluginMessages);
					pathfinderFuture = pathfindingExecutor.submit(pathfinder);
					// Diagnostic trail for the live pass (2026-09-06): the start tile and target count
					// the worker was handed, so a silent "no tiles drawn" can be placed.
					System.out.println("[shortestpath] pathfinding from " + WorldPointUtil.unpackWorldX(start) + ","
						+ WorldPointUtil.unpackWorldY(start) + "," + WorldPointUtil.unpackWorldPlane(start)
						+ " to " + ends.size() + " target(s)");
				}
			}
		});
	}

	public void restartPathfinding(int start, Set<Integer> ends)
	{
		restartPathfinding(start, ends, true);
	}

	public boolean isNearPath(int location)
	{
		List<PathStep> path;
		if (pathfinder == null || (path = pathfinder.getPath()) == null || path.isEmpty() ||
			config.recalculateDistance() < 0 || lastLocation == (lastLocation = location))
		{
			return true;
		}

		for (PathStep pathStep : path)
		{
			if (WorldPointUtil.distanceBetween(location, pathStep.getPackedPosition()) < config.recalculateDistance())
			{
				return true;
			}
		}

		return false;
	}

	public Color getPathColor()
	{
		if (pathfinder == null || !pathfinder.isDone())
		{
			return colourPathCalculating;
		}

		List<PathStep> path = pathfinder.getPath();
		if (path == null || path.isEmpty() || pathfinder.getTargets().isEmpty())
		{
			return colourPath;
		}

		if (isPathUnreachable())
		{
			return colourPathUnreachable;
		}

		return colourPath;
	}

	public boolean isPathUnreachable()
	{
		if (pathfinder == null || !pathfinder.isDone())
		{
			return false;
		}

		List<PathStep> path = pathfinder.getPath();
		if (path == null || path.isEmpty() || pathfinder.getTargets().isEmpty())
		{
			return false;
		}

		int endPoint = path.get(path.size() - 1).getPackedPosition();
		int closestTargetDistance = Integer.MAX_VALUE;
		for (int target : pathfinder.getTargets())
		{
			closestTargetDistance = Math.min(closestTargetDistance, WorldPointUtil.distanceBetween(target, endPoint));
		}

		return closestTargetDistance > unreachableTargetDistance;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!CONFIG_GROUP.equals(event.getGroup()))
		{
			return;
		}

		cacheConfigValues();

		if ("drawDebugPanel".equals(event.getKey()))
		{
			if (config.drawDebugPanel())
			{
				overlayManager.add(debugOverlayPanel);
			}
			else
			{
				overlayManager.remove(debugOverlayPanel);
			}
			return;
		}

		// Transport option changed; rerun pathfinding
		if (TRANSPORT_OPTIONS_REGEX.matcher(event.getKey()).find())
		{
			if (pathfinder != null)
			{
				restartPathfinding(pathfinder.getStart(), pathfinder.getTargets());
			}
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (pathfinderConfig == null
			|| !GameState.LOGGING_IN.equals(lastLastGameState)
			|| !GameState.LOADING.equals(lastLastGameState = lastGameState)
			|| !GameState.LOGGED_IN.equals(lastGameState = event.getGameState()))
		{
			lastLastGameState = lastGameState;
			lastGameState = event.getGameState();
			return;
		}

		pendingTasks.add(new PendingTask(client.getTickCount() + 1, pathfinderConfig::refresh));
	}

	/**
	 * Refresh the pathfinder when the player hops worlds. The new world's type
	 * (e.g. seasonal) is what drives league-mode auto-detection in
	 * {@link shortestpath.leagues.LeagueModeState}, so we need a fresh
	 * {@code PathfinderConfig.refresh()} pass after every hop.
	 */
	@Subscribe
	public void onWorldChanged(WorldChanged event)
	{
		if (pathfinderConfig == null)
		{
			return;
		}
		pendingTasks.add(new PendingTask(client.getTickCount() + 1, pathfinderConfig::refresh));
	}

	@Subscribe
	public void onPluginMessage(PluginMessage event)
	{
		if (!CONFIG_GROUP.equals(event.getNamespace()))
		{
			return;
		}

		String action = event.getName();
		if (PLUGIN_MESSAGE_PATH.equals(action))
		{
			Map<String, Object> data = event.getData();
			Object objStart = data.getOrDefault(PLUGIN_MESSAGE_START, null);
			Object objTarget = data.getOrDefault(PLUGIN_MESSAGE_TARGET, null);
			Object objConfigOverride = data.getOrDefault(PLUGIN_MESSAGE_CONFIG_OVERRIDE, null);

			@SuppressWarnings("unchecked")
			Map<String, Object> configOverride = (objConfigOverride instanceof Map<?, ?>) ? ((Map<String, Object>) objConfigOverride) : null;
			if (configOverride != null && !configOverride.isEmpty())
			{
				ShortestPathPlugin.configOverride.clear();
				for (String key : configOverride.keySet())
				{
					ShortestPathPlugin.configOverride.put(key, configOverride.get(key));
				}
				cacheConfigValues();
			}

			if (objStart == null && objTarget == null)
			{
				return;
			}

			int start = (objStart instanceof WorldPoint) ? WorldPointUtil.packWorldPoint((WorldPoint) objStart)
				: ((objStart instanceof Integer) ? ((int) objStart) : WorldPointUtil.UNDEFINED);
			if (start == WorldPointUtil.UNDEFINED)
			{
				if (client.getLocalPlayer() == null)
				{
					return;
				}
				start = WorldPointUtil.packWorldPoint(client.getLocalPlayer().getWorldLocation());
			}

			Set<Integer> targets = new HashSet<>();
			if (objTarget instanceof Integer)
			{
				int packedPoint = (Integer) objTarget;
				if (packedPoint == WorldPointUtil.UNDEFINED)
				{
					return;
				}
				targets.add(packedPoint);
			}
			else if (objTarget instanceof WorldPoint)
			{
				int packedPoint = WorldPointUtil.packWorldPoint((WorldPoint) objTarget);
				if (packedPoint == WorldPointUtil.UNDEFINED)
				{
					return;
				}
				targets.add(packedPoint);
			}
			else if (objTarget instanceof Set<?>)
			{
				@SuppressWarnings("unchecked")
				Set<Object> objTargets = (Set<Object>) objTarget;
				for (Object obj : objTargets)
				{
					int packedPoint = WorldPointUtil.UNDEFINED;
					if (obj instanceof Integer)
					{
						packedPoint = (Integer) obj;
					}
					else if (obj instanceof WorldPoint)
					{
						packedPoint = WorldPointUtil.packWorldPoint((WorldPoint) obj);
					}
					if (packedPoint == WorldPointUtil.UNDEFINED)
					{
						return;
					}
					targets.add(packedPoint);
				}
			}

			boolean useOld = targets.isEmpty() && pathfinder != null;
			restartPathfinding(start, useOld ? pathfinder.getTargets() : targets, useOld);
		}
		else if (PLUGIN_MESSAGE_CLEAR.equals(action))
		{
			configOverride.clear();
			cacheConfigValues();
			setTarget(WorldPointUtil.UNDEFINED);
		}
	}

	public void postPluginMessages()
	{
		if (pathfinder == null)
		{
			return;
		}
		if (override("postTransports", config.postTransports()))
		{
			Map<String, Object> data = new HashMap<>();
			List<WorldPoint> transportOrigins = new ArrayList<>();
			List<WorldPoint> transportDestinations = new ArrayList<>();
			List<String> transportObjectInfos = new ArrayList<>();
			List<String> transportDisplayInfos = new ArrayList<>();
			List<PathStep> currentPath = pathfinder.getPath();
			for (int i = 1; i < currentPath.size(); i++)
			{
				PathStep currentStep = currentPath.get(i - 1);
				PathStep nextStep = currentPath.get(i);
				for (Transport transport : transportsForEdge(currentStep, nextStep))
				{
					transportOrigins.add(WorldPointUtil.unpackWorldPoint(currentStep.getPackedPosition()));
					transportDestinations.add(WorldPointUtil.unpackWorldPoint(nextStep.getPackedPosition()));
					transportObjectInfos.add(transport.getObjectInfo());
					transportDisplayInfos.add(transport.getDisplayInfo());
				}
			}
			data.put("origin", transportOrigins);
			data.put("destination", transportDestinations);
			data.put("objectInfo", transportObjectInfos);
			data.put("displayInfo", transportDisplayInfos);
			eventBus.post(new PluginMessage(CONFIG_GROUP, PLUGIN_MESSAGE_TRANSPORTS, data));
		}
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		lastMenuOpenedPoint = client.getMouseCanvasPosition();
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		for (int i = 0; i < pendingTasks.size(); i++)
		{
			if (pendingTasks.get(i).check(client.getTickCount()))
			{
				pendingTasks.remove(i--).run();
			}
		}

		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null || pathfinder == null)
		{
			return;
		}

		int currentLocation = WorldPointUtil.fromLocalInstance(client, localPlayer);
		for (int target : pathfinder.getTargets())
		{
			if (WorldPointUtil.distanceBetween(currentLocation, target) < config.reachedDistance())
			{
				setTarget(WorldPointUtil.UNDEFINED);
				return;
			}
		}

		if (!startPointSet && !isNearPath(currentLocation))
		{
			if (config.cancelInstead())
			{
				setTarget(WorldPointUtil.UNDEFINED);
				return;
			}
			restartPathfinding(currentLocation, pathfinder.getTargets());
		}
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (client.isKeyPressed(KeyCode.KC_SHIFT)
			&& event.getType() == MenuAction.WALK.getId())
		{
			addMenuEntry(event, SET, TARGET, 1);
			if (pathfinder != null)
			{
				if (!pathfinder.getTargets().isEmpty())
				{
					addMenuEntry(event, SET, TARGET + ColorUtil.wrapWithColorTag(" " +
						(pathfinder.getTargets().size() + 1), JagexColors.MENU_TARGET), 1);
				}
				for (int target : pathfinder.getTargets())
				{
					if (target != WorldPointUtil.UNDEFINED)
					{
						addMenuEntry(event, SET, START, 1);
						break;
					}
				}
				int selectedTile = getSelectedWorldPoint();
				List<PathStep> path;
				if ((path = pathfinder.getPath()) != null)
				{
					for (PathStep pathStep : path)
					{
						if (pathStep.getPackedPosition() == selectedTile)
						{
							addMenuEntry(event, CLEAR, PATH, 1);
							break;
						}
					}
				}
			}
		}

		final Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);

		if (map != null)
		{
			// kewl: mouseIsOverUsableMap(), not a bare contains() -- the rectangle is only a canvas
			// rectangle when the chain resolved, and offering "Set target" over a map whose click
			// cannot be inverted just produces a menu entry that silently sets nothing.
			if (mouseIsOverUsableMap())
			{
				addMenuEntry(event, SET, TARGET, 0);
				if (pathfinder != null)
				{
					if (!pathfinder.getTargets().isEmpty())
					{
						addMenuEntry(event, SET, TARGET + ColorUtil.wrapWithColorTag(" " +
							(pathfinder.getTargets().size() + 1), JagexColors.MENU_TARGET), 0);
					}
					for (int target : pathfinder.getTargets())
					{
						if (target != WorldPointUtil.UNDEFINED)
						{
							addMenuEntry(event, SET, START, 0);
							addMenuEntry(event, CLEAR, PATH, 0);
						}
					}
				}
			}
			// Unreachable under the popup fallback: the shim's menu only ever posts the synthetic
			// "Walk here" entry, so a game-supplied "Flash icons" option never arrives to match here.
			// Kept for when the menu native lands.
			if (event.getOption().equals(FLASH_ICONS) && pathfinderConfig.hasDestination(simplify(event.getTarget())))
			{
				addMenuEntry(event, FIND_CLOSEST, event.getTarget(), 1);
			}
		}

		final Shape minimap = getMinimapClipArea();

		if (minimap != null && pathfinder != null
			&& minimap.contains(
			client.getMouseCanvasPosition().getX(),
			client.getMouseCanvasPosition().getY()))
		{
			addMenuEntry(event, CLEAR, PATH, 0);
		}

		// Unreachable under the popup fallback, same as Flash icons above: "Floating World Map" and
		// "Close Floating panel" are game menu options the shim's synthetic menu never emits.
		if (minimap != null && pathfinder != null
			&& ("Floating World Map".equals(Text.removeTags(event.getOption()))
			|| "Close Floating panel".equals(Text.removeTags(event.getOption()))))
		{
			addMenuEntry(event, CLEAR, PATH, 1);
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.BANK)
		{
			return;
		}
		pathfinderConfig.bank = event.getItemContainer();
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (pathfinder != null && event.getGroupId() == InterfaceID.FAIRYRINGS_LOG)
		{
			fairyRingPanelOpen = true;
		}

		// Populate spirit tree cache, but only once.
		// The values here almost never change, we only need to load it once.
		if (pathfinderConfig.availableSpiritTrees == null)
		{
			switch (event.getGroupId())
			{
				case InterfaceID.MENU:
					clientThread.invokeLater(() -> parseSpiritTreeWidget(false));
					break;
				case InterfaceID.MENU_NEW:
					clientThread.invokeLater(() -> parseSpiritTreeWidget(true));
					break;
			}
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.FAIRYRINGS_LOG)
		{
			fairyRingPanelOpen = false;
		}
	}

	@Subscribe
	public void onPostClientTick(PostClientTick event)
	{
		if (fairyRingPanelOpen && pathfinder != null)
		{
			scrollFairyRingPanel();
		}
	}

	private void parseSpiritTreeWidget(boolean useNewMenu)
	{
		// Referencing
		// https://github.com/trs/runelite-teleport-maps/blob/e006270494500ab8e4826903b377bb945ca9fc96/src/main/java/com/mjhylkema/TeleportMaps/components/adventureLog/SpiritTreeMap.java#L141

		Widget container;
		if (useNewMenu)
		{
			container = client.getWidget(InterfaceID.MENU_NEW, 9);
		}
		else
		{
			container = client.getWidget(InterfaceID.MENU, 3);
		}

		if (container == null)
		{
			return;
		}

		Widget[] children = container.getDynamicChildren();
		if (children == null || children.length == 0)
		{
			return;
		}

		// Tree Gnome Village is always the first row and always available;
		// quick length check before running the regex
		// Expected (old): "<col=735a28>1</col>: Tree Gnome Village" (length 39)
		// Expected (new): "<col=ffffff>1</col>: Tree Gnome Village" (length 39)
		String firstText = children[0].getText();
		if (firstText == null || firstText.length() != 39)
		{
			return;
		}

		Pattern pattern = useNewMenu ? SPIRIT_TREE_LABEL_PATTERN_MENU_NEW : SPIRIT_TREE_LABEL_PATTERN_MENU;

		Set<String> available = new HashSet<>();

		for (Widget child : children)
		{
			Matcher matcher = pattern.matcher(child.getText());
			if (!matcher.matches())
			{
				continue;
			}

			// Group 2 is the disabled color tag; if present, the tree is unavailable
			if (matcher.group(2) != null)
			{
				continue;
			}

			// Group 3 is spirit tree name
			available.add(matcher.group(3));
		}

		pathfinderConfig.availableSpiritTrees = available;

		if (pathfinder != null)
		{
			restartPathfinding(pathfinder.getStart(), pathfinder.getTargets());
		}
	}

	private void scrollFairyRingPanel()
	{
		List<PathStep> path;

		if (pathfinder == null
			|| (path = pathfinder.getPath()) == null)
		{
			return;
		}

		String fairyRingCode = null;

		for (int i = 1; i < path.size(); i++)
		{
			PathStep currentStep = path.get(i - 1);
			PathStep nextStep = path.get(i);
			for (Transport transport : transportsForEdge(currentStep, nextStep))
			{
				if (TransportType.FAIRY_RING.equals(transport.getType()))
				{
					fairyRingCode = transport.getDisplayInfo();
				}
			}
		}
		if (fairyRingCode == null)
		{
			return;
		}

		Widget codeWidget = null;

		Widget favesPanel = client.getWidget(InterfaceID.FairyringsLog.FAVES);
		if (favesPanel != null)
		{
			for (Widget widget : favesPanel.getStaticChildren())
			{
				if (widget != null)
				{
					String widgetText = widget.getText();
					if ((fairyRingCode.equals(widgetText)
						|| ("(Shortest Path) " + fairyRingCode).equals(widgetText)))
					{
						codeWidget = widget;
						break;
					}
				}
			}
		}

		Widget contentsList = client.getWidget(InterfaceID.FairyringsLog.CONTENTS);
		if (contentsList != null && codeWidget == null)
		{
			for (Widget widget : contentsList.getDynamicChildren())
			{
				if (widget != null)
				{
					String widgetText = widget.getText();
					if ((fairyRingCode.equals(widgetText)
						|| ("(Shortest Path) " + fairyRingCode).equals(widgetText)))
					{
						codeWidget = widget;
						break;
					}
				}
			}
		}

		if (codeWidget == null)
		{
			return;
		}

		codeWidget.setTextColor(0x00FF00);
		String codeWidgetText = codeWidget.getText();
		if (codeWidgetText != null && !codeWidgetText.contains("(Shortest Path)"))
		{
			codeWidget.setText("(Shortest Path) " + codeWidgetText);
		}

		if (contentsList == null)
		{
			return;
		}

		int panelScrollY = Math.min(
			codeWidget.getRelativeY(),
			contentsList.getScrollHeight() - contentsList.getHeight()
		);

		contentsList.setScrollY(panelScrollY);
		contentsList.revalidateScroll();

		client.runScript(
			ScriptID.UPDATE_SCROLLBAR,
			InterfaceID.FairyringsLog.SCROLLBAR,
			InterfaceID.FairyringsLog.CONTENTS,
			panelScrollY
		);
	}

	public CollisionMap getMap()
	{
		return pathfinderConfig.getMap();
	}

	/**
	 * WARNING: This is a legacy wrapper for coarse display-oriented callers only.
	 * <p>
	 * It collapses banked/unbanked transport availability into a single view via
	 * PathfinderConfig.getTransports(), which is not valid for path-state-sensitive logic.
	 * <p>
	 * Do not use this for reasoning about which transports are available at a specific
	 * step of a path. Use PathfinderConfig.getTransportAvailability(boolean) and the
	 * path's PathStep state instead.
	 */
	public Map<Integer, Set<Transport>> getTransports()
	{
		return pathfinderConfig.getTransports();
	}

	/**
	 * This reconstructs the candidate transports for a rendered path edge from the current path state.
	 * <p>
	 * The important detail is that path display logic is edge-based, not node-based:
	 * - origin position comes from currentStep
	 * - destination position comes from nextStep
	 * - the applicable transport set may depend on whether the edge transitions into banked state
	 * <p>
	 * That last point is the awkward one. Banking is not represented as its own explicit path edge;
	 * instead the "becomes banked" state change is conflated into the movement/transport edge that
	 * reaches the banked destination step. As a result, callers cannot safely resolve transports from
	 * a single PathStep alone: using only currentStep can miss bank-gated transports, while using only
	 * nextStep loses the origin tile of the edge. This helper therefore takes both steps and resolves
	 * transports for the edge between them.
	 * <p>
	 * This is still only a fallback for display code and remains inherently ambiguous when multiple
	 * valid transports share the same origin/destination pair under the same edge state. The more
	 * structural fix would be to model reconstructed paths in terms of explicit edges, or otherwise
	 * carry richer per-edge metadata, instead of repeatedly re-deriving transport candidates from
	 * adjacent path steps.
	 * <p>
	 * Note that this function also performs filtering by the transport target, so callers of this
	 * function can directly iterate over the returned transports.
	 */
	public Set<Transport> transportsForEdge(PathStep currentStep, PathStep nextStep)
	{
		if (currentStep == null || nextStep == null)
		{
			return Set.of();
		}
		boolean bankVisited = currentStep.isBankVisited() || nextStep.isBankVisited();
		// Get the transports which start from the position of starting step.
		Set<Transport> stepTransports = new HashSet<>(
			pathfinderConfig.getTransportsPacked(bankVisited)
				.getOrDefault(currentStep.getPackedPosition(), Set.of()));
		// Add the teleports, which might be used from anywhere.
		stepTransports.addAll(pathfinderConfig.getUsableTeleports(bankVisited));
		// Remove transports which do not target the correct location.
		stepTransports.removeIf(transport -> transport.getDestination() != nextStep.getPackedPosition());
		// Remove teleports that share destinations with a local transport type on this edge.
		// For example, if the path uses a QUETZAL (local) transport, suppress QUETZAL_WHISTLE hints.
		// Also suppress them when the edge distance is within the shared type's radius threshold,
		// which occurs when the path is simply walking to a landing site (not teleporting to it).
		Set<TransportType> localTypes = EnumSet.noneOf(TransportType.class);
		for (Transport t : stepTransports)
		{
			if (t.getOrigin() != Transport.UNDEFINED_ORIGIN && t.getType() != null)
			{
				localTypes.add(t.getType());
			}
		}
		int edgeDistance = WorldPointUtil.distanceBetween2D(currentStep.getPackedPosition(), nextStep.getPackedPosition());
		stepTransports.removeIf(t ->
		{
			if (t.getOrigin() != Transport.UNDEFINED_ORIGIN || t.getType() == null)
			{
				return false; // keep local transports
			}
			TransportType sharedType = t.getType().sharesDestinationsWith();
			if (sharedType == null)
			{
				return false; // not a shared-destination teleport, keep it
			}
			// Suppress if a local transport of the shared type is present on this edge (Issue 1),
			// or if the edge is within the shared type's radius threshold, meaning the path is
			// walking to the landing site rather than teleporting there (Issue 2).
			return localTypes.contains(sharedType)
				|| (sharedType.getRadiusThreshold() != null && edgeDistance <= sharedType.getRadiusThreshold());
		});
		return stepTransports;
	}

	public PathStep nextPathStep(List<PathStep> path, int index)
	{
		if (path == null || index < 0 || index + 1 >= path.size())
		{
			return null;
		}
		return path.get(index + 1);
	}

	/**
	 * Checks if the destination is inside POH and looks ahead in the path to find the exit transport.
	 * If the immediate exit leads to a fairy ring or other notable transport shortly after,
	 * that information is included instead.
	 *
	 * @param destination  The destination point to check
	 * @param path         The full path
	 * @param currentIndex The current index in the path
	 * @return The display info of the POH exit transport, or null if not applicable
	 */
	public String getPohExitInfo(int destination, List<PathStep> path, int currentIndex)
	{
		if (path == null || currentIndex < 0)
		{
			return null;
		}

		int destX = WorldPointUtil.unpackWorldX(destination);
		int destY = WorldPointUtil.unpackWorldY(destination);

		// Check if destination is inside POH
		if (!isInsidePoh(destX, destY))
		{
			return null;
		}

		String immediateExitInfo = null;

		// Look ahead in the path to find the next transport that exits POH
		for (int i = currentIndex + 1; i < path.size() - 1; i++)
		{
			int stepLocation = path.get(i).getPackedPosition();
			int nextLocation = path.get(i + 1).getPackedPosition();

			int stepX = WorldPointUtil.unpackWorldX(stepLocation);
			int stepY = WorldPointUtil.unpackWorldY(stepLocation);
			int nextX = WorldPointUtil.unpackWorldX(nextLocation);
			int nextY = WorldPointUtil.unpackWorldY(nextLocation);

			// Check if this step is inside POH but next step is outside (exit transport)
			boolean stepInsidePoh = isInsidePoh(stepX, stepY);
			boolean nextInsidePoh = isInsidePoh(nextX, nextY);

			if (stepInsidePoh && !nextInsidePoh)
			{
				// Found the exit transport - get its display info using bank-aware lookup
				PathStep currentStep = path.get(i);
				PathStep nextStep = path.get(i + 1);
				for (Transport transport : transportsForEdge(currentStep, nextStep))
				{
					String exitInfo = transport.getDisplayInfo();
					if (exitInfo != null && !exitInfo.isEmpty())
					{
						TransportType exitType = transport.getType();
						if (TransportType.TELEPORTATION_BOX.equals(exitType))
						{
							String objInfo = transport.getObjectInfo();
							if (objInfo != null && objInfo.contains("Amulet of Glory"))
							{
								immediateExitInfo = "Mounted Glory: " + exitInfo;
							}
							else if (objInfo != null && objInfo.contains("Mythical cape"))
							{
								immediateExitInfo = "Mythical Cape: " + exitInfo;
							}
							else if (objInfo != null && objInfo.contains("Xeric's Talisman"))
							{
								immediateExitInfo = "Xeric's Talisman: " + exitInfo;
							}
							else if (objInfo != null && objInfo.contains("Digsite"))
							{
								immediateExitInfo = "Digsite Pendant: " + exitInfo;
							}
							else
							{
								immediateExitInfo = "Jewelry Box: " + exitInfo;
							}
						}
						else if (TransportType.TELEPORTATION_PORTAL_POH.equals(exitType))
						{
							immediateExitInfo = "Nexus: " + exitInfo;
						}
						else if (TransportType.FAIRY_RING.equals(exitType))
						{
							immediateExitInfo = "Fairy Ring " + exitInfo;
						}
						else if (TransportType.SPIRIT_TREE.equals(exitType))
						{
							immediateExitInfo = "Spirit Tree: " + exitInfo;
						}
						else if (TransportType.WILDERNESS_OBELISK.equals(exitType))
						{
							immediateExitInfo = "Obelisk: " + exitInfo;
						}
						else
						{
							immediateExitInfo = exitInfo;
						}
					}
					break;
				}
				break;
			}

			// If we've left POH without finding a transport, stop looking
			if (!stepInsidePoh)
			{
				break;
			}
		}

		return immediateExitInfo;
	}

	private Color override(String configOverrideKey, Color defaultValue)
	{
		if (!configOverride.isEmpty())
		{
			Object value = configOverride.get(configOverrideKey);
			if (value instanceof Color)
			{
				return (Color) value;
			}
		}
		return defaultValue;
	}

	private TileCounter override(String configOverrideKey, TileCounter defaultValue)
	{
		if (!configOverride.isEmpty())
		{
			Object value = configOverride.get(configOverrideKey);
			if (value instanceof String)
			{
				TileCounter tileCounter = TileCounter.fromType((String) value);
				if (tileCounter != null)
				{
					return tileCounter;
				}
			}
		}
		return defaultValue;
	}

	private TileStyle override(String configOverrideKey, TileStyle defaultValue)
	{
		if (!configOverride.isEmpty())
		{
			Object value = configOverride.get(configOverrideKey);
			if (value instanceof String)
			{
				TileStyle tileStyle = TileStyle.fromType((String) value);
				if (tileStyle != null)
				{
					return tileStyle;
				}
			}
		}
		return defaultValue;
	}

	private void cacheConfigValues()
	{
		drawCollisionMap = override("drawCollisionMap", config.drawCollisionMap());
		drawMap = override("drawMap", config.drawMap());
		drawMinimap = override("drawMinimap", config.drawMinimap());
		drawTiles = override("drawTiles", config.drawTiles());
		drawTransports = override("drawTransports", config.drawTransports());
		showTransportInfo = override("showTransportInfo", config.showTransportInfo());
		showBankPickupInfo = override("showBankPickupInfo", config.showBankPickupInfo());

		colourCollisionMap = override("colourCollisionMap", config.colourCollisionMap());
		colourPath = override("colourPath", config.colourPath());
		colourPathCalculating = override("colourPathCalculating", config.colourPathCalculating());
		colourPathUnreachable = override("colourPathUnreachable", config.colourPathUnreachable());
		colourText = override("colourText", config.colourText());
		colourTransports = override("colourTransports", config.colourTransports());

		tileCounterStep = override("tileCounterStep", config.tileCounterStep());
		unreachableTargetDistance = override("unreachableTargetDistanceThreshold", config.unreachableTargetDistance());
		unreachableText = config.unreachableText();

		showTileCounter = override("showTileCounter", config.showTileCounter());
		pathStyle = override("pathStyle", config.pathStyle());
	}

	private String simplify(String text)
	{
		return Text.removeTags(text).toLowerCase()
			.replaceAll("[^a-zA-Z ]", "")
			.replace(" ", "_")
			.replace("__", "_");
	}

	private void onMenuOptionClicked(MenuEntry entry)
	{
		// kewl: every SET row resolves through setTargetFromSelection/the guarded setStart below, never
		// through setTarget(getSelectedWorldPoint()) directly. UNDEFINED is BOTH "the click could not be
		// turned into a tile" and "clear the target", and setTargets(empty) is a full teardown -- cancel
		// the pathfinder, null it, drop the marker, clear startPointSet. Passing a failed resolve
		// straight in is what made "Set target" wipe the plugin (user report 2026-09-07). The CLEAR row
		// below still calls setTarget(UNDEFINED) and still clears, which is the point: only an explicit
		// clear clears.
		if (entry.getOption().equals(SET) && entry.getTarget().equals(TARGET))
		{
			setTargetFromSelection(false);
		}
		else if (entry.getOption().equals(SET) && pathfinder != null && entry.getTarget().equals(TARGET +
			ColorUtil.wrapWithColorTag(" " + (pathfinder.getTargets().size() + 1), JagexColors.MENU_TARGET)))
		{
			setTargetFromSelection(true);
		}
		else if (entry.getOption().equals(SET) && entry.getTarget().equals(START))
		{
			int selectedStart = getSelectedWorldPoint();
			if (selectedStart == WorldPointUtil.UNDEFINED)
			{
				// setStart(UNDEFINED) is not inert either: it sets startPointSet and restarts the
				// pathfinder from a non-tile, which strands the path at the origin.
				noteSelectionFailure("\"Set start\" could not be resolved to a tile");
				return;
			}
			setStart(selectedStart);
		}
		else if (entry.getOption().equals(CLEAR) && entry.getTarget().equals(PATH))
		{
			setTarget(WorldPointUtil.UNDEFINED);
		}
		else if (entry.getOption().equals(FIND_CLOSEST))
		{
			setTargets(pathfinderConfig.getDestinations(simplify(entry.getTarget())), true);
		}
	}

	/**
	 * Where the tile a "Set target" click means is allowed to come from.
	 *
	 * <p>Named branches rather than nested ifs, because the branch table IS the bug this replaces: an
	 * older version made the SCENE branch exclusive to "the map looks closed", so once the world-map
	 * group had been loaded even once, a right-click on the SCENE fell through to a map branch that
	 * cannot resolve on this build and returned UNDEFINED -- and setTarget(UNDEFINED) is a CLEAR, so
	 * pressing "Set target" tore down the path the user had just asked for. That is the user's report
	 * of 2026-09-07 ("shortestpath doesnt do anything when setting a target").</p>
	 *
	 * <p>The next version over-corrected and produced the user's SECOND report of the same day: "when i
	 * open the worldmap and choose a location in it. its fucked up because it chooses on the gamescreen
	 * not worldmap". Everything that was not a trusted map click fell through to the scene, so a click
	 * ON THE OPEN MAP -- whose rectangle this build cannot resolve -- silently became a target at
	 * whatever scene tile happened to be parked underneath. The three refusal constants below exist so
	 * that case has somewhere to go that is neither "the map" nor "the scene".</p>
	 */
	enum SelectionSource
	{
		/** (a) on screen, (b) inside it, (c) invertible. Invert the click through the map projection. */
		MAP,
		/** (b) answered NO, or (a) said the map is not on screen. Use the parked scene tile. */
		SCENE,
		/**
		 * (a) YES, (b) UNANSWERABLE -- the map is on screen and we cannot tell whether this click was
		 * on it. Resolves to nothing, on purpose. See {@link #selectionSourceFor} for why this is not
		 * allowed to fall through to the scene.
		 */
		MAP_CONTAINMENT_UNKNOWN,
		/**
		 * (a) YES, (b) YES, (c) NO -- the click really is on the map and the map cannot turn it into a
		 * tile. Resolves to nothing; the inversion refusal names the one action that unblocks it.
		 */
		MAP_NOT_INVERTIBLE,
		/** Nothing to resolve to at all: the map is not on screen and no scene tile is parked. */
		NONE
	}

	/** True for every branch that means "leave the existing target exactly as it is". */
	static boolean isRefusal(SelectionSource source)
	{
		return source == SelectionSource.MAP_CONTAINMENT_UNKNOWN
			|| source == SelectionSource.MAP_NOT_INVERTIBLE
			|| source == SelectionSource.NONE;
	}

	/**
	 * Escape hatch for the one failure mode the design below deliberately accepts. Read once, off a
	 * property, so it cannot be flipped by anything the client does at runtime.
	 *
	 * <p>{@code -Dkewl.shortestpath.sceneUnderOpenMap=true} restores the old behaviour: while the map
	 * is on screen and containment is unanswerable, resolve from the scene anyway. It exists because
	 * the refusal is keyed on {@code Widget.isHidden()} for the world-map group, and if that ever
	 * misreports a CLOSED map as open, "Set target" on the scene would stop working with no way back
	 * short of a rebuild. The refusal message names this flag, so that state is one log line and one
	 * flag from being recoverable instead of a rebuild. Default false: the failure it restores is the
	 * one the user actually reported.</p>
	 */
	static final String SCENE_UNDER_OPEN_MAP_PROPERTY = "kewl.shortestpath.sceneUnderOpenMap";

	private static final boolean SCENE_UNDER_OPEN_MAP =
		Boolean.getBoolean(SCENE_UNDER_OPEN_MAP_PROPERTY);

	/**
	 * The whole branch table, pure so it can be asserted without a client.
	 *
	 * <p>THREE QUESTIONS, asked in order, each with its own input. They used to be two, and the missing
	 * one is why a click on the open map became a scene target:</p>
	 *
	 * <ol>
	 *   <li>(a) IS THE MAP ON SCREEN? {@code mapOnScreen}, from {@link WorldMap#presenceRefusalFor} --
	 *       loaded and not hidden, and nothing else. No rectangle, no scale.</li>
	 *   <li>(b) DID THIS CLICK LAND ON IT? {@code containmentRefusal == null} says the question is
	 *       ANSWERABLE (the rectangle is a real canvas rectangle); {@code clickInsideMapRect} is the
	 *       answer, and is meaningless when the refusal is non-null.</li>
	 *   <li>(c) CAN THIS POINT BECOME A TILE? {@code inversionRefusal == null}. Needs the centre and
	 *       the scale on top of the rectangle.</li>
	 * </ol>
	 *
	 * <p>THE DECISION, stated plainly because it trades one failure for another. When (a) is yes and
	 * (b) is UNANSWERABLE, this REFUSES ({@link SelectionSource#MAP_CONTAINMENT_UNKNOWN}) rather than
	 * falling back to the scene. The two failure modes:</p>
	 *
	 * <ul>
	 *   <li>REFUSING: "Set target" does nothing while the map is open, and says why in one line. The
	 *       user recovers by closing the map -- the scene path is untouched and works immediately.</li>
	 *   <li>FALLING BACK: the user gets a target they did not pick, computed from a scene tile under a
	 *       map they were looking at, and auto-walk then walks there. Nothing about it looks wrong.</li>
	 * </ul>
	 *
	 * <p>Refusing is chosen. A refusal is visible, one action from recovery, and cannot move the
	 * character; a wrong target is invisible, and the user has now reported it as a bug. "No target" is
	 * a worse product than "the right target" and a better one than "a target somewhere else".</p>
	 *
	 * <p>THE LOAD-BEARING CONSTRAINT, and how it is made structural rather than merely likely: a scene
	 * right-click WITH THE MAP CLOSED must keep working. That is the regression fixed hours earlier and
	 * it must not come back. The guarantee is the FIRST statement of this method -- when
	 * {@code mapOnScreen} is false the method returns from the scene/none pair and no map input is read
	 * at all. Not a condition among conditions: an early return, above every line that mentions the
	 * map, so no future addition to the map reasoning can be reached in that state. {@code mapOnScreen}
	 * comes from presence only, so a geometry misread cannot manufacture an open map either.
	 * {@code SelectedWorldPointBranchTest} asserts it exhaustively: for BOTH scene states and all
	 * combinations of every other input, {@code mapOnScreen == false} yields SCENE or NONE and never a
	 * refusal.</p>
	 *
	 * <p>Where (b) answers NO, the scene tile is the answer, exactly as before. That is safe here for a
	 * reason specific to this client: kewl's MenuPopup parks the tile the right-click landed on when it
	 * opens (MenuPopup.open), and WorldView.getSelectedSceneTile() hands that parked tile back for as
	 * long as the menu is up. So while the user is looking at the popup row they are about to click,
	 * the "selected scene tile" is exactly where they right-clicked.</p>
	 */
	static SelectionSource selectionSourceFor(boolean mapOnScreen, String containmentRefusal,
		boolean clickInsideMapRect, String inversionRefusal, boolean sceneTileAvailable,
		boolean sceneFallbackUnderOpenMap)
	{
		// (a) THE MAP IS NOT ON SCREEN. Structural guarantee: this is the first statement and it
		// returns, so with the map closed or never opened nothing below can run, whatever it comes to
		// say about rectangles. The scene click cannot be taken away by map reasoning it never reaches.
		if (!mapOnScreen)
		{
			return sceneTileAvailable ? SelectionSource.SCENE : SelectionSource.NONE;
		}

		// (b) UNANSWERABLE: the map is up and its rectangle is not a canvas rectangle, so "was this
		// click on the map?" has no answer. Refuse -- see the decision in this method's doc.
		if (containmentRefusal != null)
		{
			if (sceneFallbackUnderOpenMap)
			{
				return sceneTileAvailable ? SelectionSource.SCENE : SelectionSource.NONE;
			}
			return SelectionSource.MAP_CONTAINMENT_UNKNOWN;
		}

		// (b) NO: answerable, and the answer is that the click was outside the map. The scene owns it.
		if (!clickInsideMapRect)
		{
			return sceneTileAvailable ? SelectionSource.SCENE : SelectionSource.NONE;
		}

		// (b) YES from here down: the click is ON THE MAP. The scene is no longer a candidate for it at
		// all -- whatever tile is parked under the map is not what the user pointed at.
		if (inversionRefusal != null)
		{
			return SelectionSource.MAP_NOT_INVERTIBLE;
		}
		return SelectionSource.MAP;
	}

	private int getSelectedWorldPoint()
	{
		net.runelite.api.widgets.Widget mapContainer = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		// The point this click is resolved from -- the same one the map branch inverts. A menu is open
		// by the time the entry is invoked, so the live cursor has already moved off the row the user
		// aimed at; lastMenuOpenedPoint is where they actually right-clicked.
		Point click = client.isMenuOpen() && lastMenuOpenedPoint != null
			? lastMenuOpenedPoint : client.getMouseCanvasPosition();
		// The three questions, each asked of the thing it actually needs. (a) touches no geometry, so
		// the scene fallback below cannot be lost to a rectangle that failed to resolve.
		String presenceRefusal = WorldMap.presenceRefusalFor(mapContainer);
		String containmentRefusal = WorldMap.containmentRefusalFor(mapContainer);
		String inversionRefusal = WorldMap.inversionRefusalFor(mapContainer, client.getWorldMap());
		// pointIsOnMapSurface is the identical containment test the MENU asks, so the entry that is
		// offered and the resolver that answers it cannot disagree.
		boolean insideMap = pointIsOnMapSurface(mapContainer, click);
		net.runelite.api.Tile sceneTile = client.getTopLevelWorldView().getSelectedSceneTile();
		SelectionSource source = selectionSourceFor(presenceRefusal == null, containmentRefusal,
			insideMap, inversionRefusal, sceneTile != null, SCENE_UNDER_OPEN_MAP);

		if (System.getenv("KEWL_LOG") != null)
		{
			// One line, and it says WHICH QUESTION failed: a(on screen) / b(on the map) / c(invertible).
			// A live report of "set target did nothing" has to be diagnosable from this line alone.
			System.out.println("[shortestpath] selected world point: branch=" + source
				+ (isRefusal(source) ? " (REFUSED -- nothing resolved, the target is left alone)" : "")
				+ " a(onScreen)=" + (presenceRefusal == null ? "YES" : "NO: " + presenceRefusal)
				+ " b(onMap)=" + (containmentRefusal != null
					? "UNANSWERABLE: " + containmentRefusal : (insideMap ? "YES" : "NO"))
				+ " c(invertible)=" + (inversionRefusal == null ? "YES" : "NO: " + inversionRefusal)
				+ " click=" + (click == null ? "null" : click.getX() + "," + click.getY())
				+ " sceneTile=" + sceneTile);
		}

		switch (source)
		{
			case MAP:
			{
				// A map click becomes a target through calculateMapPoint, which is the FORWARD map
				// projection run backwards, so it inherits every error the forward one has. We only get
				// here once question (c) has vouched for the rectangle AND the centre AND the scale --
				// the same predicate the map overlays draw on.
				int mapPoint = calculateMapPoint(click.getX(), click.getY());
				if (mapPoint == WorldPointUtil.UNDEFINED)
				{
					// The map branch was legitimately taken and still could not answer. That failure
					// belongs to THIS click alone: no scene answer is being deleted, and it is not the
					// user asking to clear the path.
					return failSelection("(c) the world-map click at " + click.getX() + "," + click.getY()
						+ " is on the map and could not be inverted into a tile");
				}
				selectionFailure = null;
				return mapPoint;
			}
			case SCENE:
				// Both notes below are gated on the map being ON SCREEN. A closed or never-opened map
				// explaining why it cannot resolve clicks is pure noise -- that is its ordinary state,
				// and the scene is the only surface anyone is asking about.
				if (presenceRefusal != null)
				{
					// Nothing to say: the map is not up, the scene answered, that is the normal path.
					selectionFailure = null;
					return WorldPointUtil.fromLocalInstance(client, sceneTile.getLocalLocation());
				}
				if (containmentRefusal != null)
				{
					// Reachable only under the escape-hatch property, since without it an unanswerable
					// containment refuses. Said once, and deliberately NOT phrased as "your click was
					// refused": this click WAS answered, from the scene.
					noteMapRefusal("the world map cannot say whether a click is on it ("
						+ containmentRefusal + ") and -D" + SCENE_UNDER_OPEN_MAP_PROPERTY + "=true is set,"
						+ " so clicks are being resolved from the SCENE while the map is open. A click ON"
						+ " the map will set a target somewhere you did not choose");
				}
				else if (inversionRefusal != null)
				{
					noteMapRefusal("the world map cannot resolve clicks (" + inversionRefusal + ")."
						+ " Right-clicking a tile in the scene still sets a target and is what is being"
						+ " used");
				}
				selectionFailure = null;
				return WorldPointUtil.fromLocalInstance(client, sceneTile.getLocalLocation());
			case MAP_CONTAINMENT_UNKNOWN:
				// (a) yes, (b) unanswerable. The deliberate refusal: the user is looking at the open map,
				// and the scene tile parked underneath is not what they pointed at. Close the map and the
				// scene path works immediately -- that recovery is why this is a refusal and not a guess.
				return failSelection("(b) the WORLD MAP IS OPEN and this build cannot say whether the"
					+ " click at " + (click == null ? "?" : click.getX() + "," + click.getY())
					+ " landed on it (" + containmentRefusal + "). Refusing on purpose: resolving it from"
					+ " the scene would set a target at whatever tile is under the map, which is not what"
					+ " was clicked. CLOSE THE WORLD MAP and right-click the tile in the game scene --"
					+ " that path is unaffected. To resolve from the scene anyway, -D"
					+ SCENE_UNDER_OPEN_MAP_PROPERTY + "=true");
			case MAP_NOT_INVERTIBLE:
				// (a) yes, (b) yes, (c) no. The click really is on the map; the map just cannot invert it
				// yet. The refusal carries the one action that fixes it (calibrate the scale by dragging).
				return failSelection("(c) the click at "
					+ (click == null ? "?" : click.getX() + "," + click.getY())
					+ " IS on the open world map, and the map cannot turn it into a tile ("
					+ inversionRefusal + ")");
			default:
				// No scene tile parked and no map click to invert. There is nothing to resolve TO, so the
				// caller must leave whatever target already exists exactly as it is.
				return failSelection(presenceRefusal == null
					? "no scene tile was selected for this click, and the click was not on the world map"
					: "(a) no scene tile was selected for this click and " + presenceRefusal);
		}
	}

	/** Last map-geometry refusal mentioned; keyed on the text so each distinct reason is said once. */
	private String loggedMapClickRefusal = "";

	/** Last "could not resolve a target" reason mentioned, deduplicated the same way. */
	private String loggedSelectionFailure = "";

	/** Why the most recent {@link #getSelectedWorldPoint} returned UNDEFINED; null when it resolved. */
	private String selectionFailure = null;

	/**
	 * Records why a click could not be resolved, then answers UNDEFINED -- so a caller can tell a
	 * FAILED resolve apart from the user asking to clear the target, which is the same int.
	 */
	private int failSelection(String why)
	{
		selectionFailure = why;
		return WorldPointUtil.UNDEFINED;
	}

	private void noteMapRefusal(String message)
	{
		if (!message.equals(loggedMapClickRefusal))
		{
			loggedMapClickRefusal = message;
			System.out.println("[shortestpath] " + message);
		}
	}

	private void noteSelectionFailure(String what)
	{
		String message = what + ", so the current target is left alone: "
			+ (selectionFailure == null ? "nothing was selected" : selectionFailure);
		if (!message.equals(loggedSelectionFailure))
		{
			loggedSelectionFailure = message;
			System.out.println("[shortestpath] " + message);
		}
	}

	/**
	 * Whether a right-click over the world map may offer the map-branch entries.
	 *
	 * <p>Defined as "the resolver would take the MAP branch for this click", by asking
	 * {@link #selectionSourceFor} itself with the cursor's position. Not "the same containment test":
	 * the SAME FUNCTION, so the menu cannot offer a row the resolver will refuse and cannot withhold
	 * one it would have answered. These two were once decided differently, and that was a real defect
	 * -- the menu asked {@code map.getBounds().contains(mouse)} against a parent-relative rectangle
	 * while the resolver asked only "is the map open at all", so a shift-right-click anywhere on the
	 * scene resolved through the map branch and set a target computed from a map pixel nobody had
	 * clicked.</p>
	 */
	private boolean mouseIsOverUsableMap()
	{
		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		return selectionSourceFor(WorldMap.isOnScreen(map),
			WorldMap.containmentRefusalFor(map),
			pointIsOnMapSurface(map, client.getMouseCanvasPosition()),
			WorldMap.inversionRefusalFor(map, client.getWorldMap()),
			client.getTopLevelWorldView().getSelectedSceneTile() != null,
			SCENE_UNDER_OPEN_MAP) == SelectionSource.MAP;
	}

	/**
	 * Question (b) alone: is this canvas point on the map's surface? The rectangle is consulted only
	 * after {@link WorldMap#containmentRefusalFor} has vouched for it AS A RECTANGLE, so
	 * {@code contains} is meaningful rather than a test against a parent-relative rectangle that could
	 * answer either way for the wrong reason.
	 *
	 * <p>Containment, not {@link WorldMap#refusalFor}. Asking the inversion question here would fold
	 * the SCALE into "did the click land on the map", and a false answer there does not mean "the
	 * click was elsewhere" -- it means "we could not tell". False from this method is only ever
	 * consumed alongside the containment refusal that explains it: {@link #selectionSourceFor} reads
	 * the refusal FIRST and never reaches the boolean when it is non-null.</p>
	 */
	private boolean pointIsOnMapSurface(Widget map, Point at)
	{
		if (at == null || WorldMap.containmentRefusalFor(map) != null)
		{
			return false;
		}
		return map.getBounds().contains(at.getX(), at.getY());
	}

	/**
	 * "Set target" / "Set target N": resolve first, and only act if the resolve produced a tile.
	 *
	 * <p>{@link #setTarget(int)} with {@link WorldPointUtil#UNDEFINED} is not a no-op -- it reaches
	 * {@link #setTargets} with an empty set, which cancels the pathfinder, nulls it, removes the world
	 * map marker and clears {@code startPointSet}. So a FAILED resolve routed through it is
	 * indistinguishable from the user pressing "Clear path", and the visible behaviour is a click on
	 * "Set target" silently deleting the path that was already there. Splitting the two here is the
	 * guard the caller side of the fix needed: a failure leaves the existing target exactly as it was
	 * and says why (once per distinct reason), while the CLEAR menu row, the clear hotkey and the
	 * PLUGIN_MESSAGE_CLEAR path keep calling {@code setTarget(UNDEFINED)} and keep clearing.</p>
	 */
	private void setTargetFromSelection(boolean append)
	{
		int selected = getSelectedWorldPoint();
		if (selected == WorldPointUtil.UNDEFINED)
		{
			noteSelectionFailure("\"Set target\" could not be resolved to a tile");
			return;
		}
		setTarget(selected, append);
	}

	private void setTarget(int target)
	{
		setTarget(target, false);
	}

	private void setTarget(int target, boolean append)
	{
		Set<Integer> targets = new HashSet<>();
		if (target != WorldPointUtil.UNDEFINED)
		{
			targets.add(target);
		}
		setTargets(targets, append);
	}

	private void setTargets(Set<Integer> targets, boolean append)
	{
		if (targets == null || targets.isEmpty())
		{
			synchronized (pathfinderMutex)
			{
				if (pathfinder != null)
				{
					pathfinder.cancel();
				}
				pathfinder = null;
			}

			worldMapPointManager.removeIf(x -> x == marker);
			marker = null;
			startPointSet = false;
		}
		else
		{
			Player localPlayer = client.getLocalPlayer();
			if (!startPointSet && localPlayer == null)
			{
				return;
			}
			worldMapPointManager.removeIf(x -> x == marker);
			if (targets.size() == 1)
			{
				marker = new WorldMapPoint(WorldPointUtil.unpackWorldPoint(targets.iterator().next()), MARKER_IMAGE);
				marker.setName("Target");
				marker.setTarget(marker.getWorldPoint());
				marker.setJumpOnClick(true);
				worldMapPointManager.add(marker);
			}

			int start = WorldPointUtil.fromLocalInstance(client, localPlayer);
			lastLocation = start;
			if (startPointSet && pathfinder != null)
			{
				start = pathfinder.getStart();
			}
			Set<Integer> destinations = new HashSet<>(targets);
			if (pathfinder != null && append)
			{
				destinations.addAll(pathfinder.getTargets());
			}
			restartPathfinding(start, destinations, append);
		}
	}

	private void setStart(int start)
	{
		if (pathfinder == null)
		{
			return;
		}
		startPointSet = true;
		restartPathfinding(start, pathfinder.getTargets());
	}

	public int calculateMapPoint(int pointX, int pointY)
	{
		WorldMap worldMap = client.getWorldMap();
		float zoom = worldMap.getWorldMapZoom();
		int mapPoint = WorldPointUtil.packWorldPoint(worldMap.getWorldMapPosition().getX(), worldMap.getWorldMapPosition().getY(), 0);
		int middleX = mapWorldPointToGraphicsPointX(mapPoint);
		int middleY = mapWorldPointToGraphicsPointY(mapPoint);

		if (pointX == Integer.MIN_VALUE || pointY == Integer.MIN_VALUE ||
			middleX == Integer.MIN_VALUE || middleY == Integer.MIN_VALUE)
		{
			return WorldPointUtil.UNDEFINED;
		}

		final int dx = (int) ((pointX - middleX) / zoom);
		final int dy = (int) ((-(pointY - middleY)) / zoom);

		return WorldPointUtil.dxdy(mapPoint, dx, dy);
	}

	/**
	 * kewl: the forward map projection refuses at its own boundary, not just at its callers'.
	 *
	 * <p>Upstream's only guard is {@code map != null}, which is exactly the guard that is not enough
	 * on this client: the world-map group stays loaded while the map is closed, and a component's
	 * stored x/y is parent-relative until its chain resolves. Everything downstream of these two
	 * methods -- the overlay's fills, the dashed transport lines, the collision-extent walk, and
	 * {@link #calculateMapPoint}, which is these two run BACKWARDS to turn a click into a tile --
	 * already treats {@code Integer.MIN_VALUE} as "no answer" and drops the point. So the honest place
	 * to refuse is here, once, where the rectangle is actually read.</p>
	 *
	 * <p>That makes the wrong-target failure structurally impossible rather than merely gated: a
	 * caller that forgets the predicate gets no coordinate instead of a plausible-looking one that is
	 * a fixed vector away from the truth.</p>
	 */
	private boolean mapProjectionUsable(Widget map)
	{
		return WorldMap.refusalFor(map, client.getWorldMap()) == null;
	}

	public int mapWorldPointToGraphicsPointX(int packedWorldPoint)
	{
		WorldMap worldMap = client.getWorldMap();

		float pixelsPerTile = worldMap.getWorldMapZoom();

		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (mapProjectionUsable(map))
		{
			Rectangle worldMapRect = map.getBounds();

			int widthInTiles = (int) Math.ceil(worldMapRect.getWidth() / pixelsPerTile);

			Point worldMapPosition = worldMap.getWorldMapPosition();

			int xTileOffset = WorldPointUtil.unpackWorldX(packedWorldPoint) + widthInTiles / 2 - worldMapPosition.getX();

			int xGraphDiff = ((int) (xTileOffset * pixelsPerTile));
			xGraphDiff += (int) (pixelsPerTile - Math.ceil(pixelsPerTile / 2));
			xGraphDiff += (int) worldMapRect.getX();

			return xGraphDiff;
		}
		return Integer.MIN_VALUE;
	}

	public int mapWorldPointToGraphicsPointY(int packedWorldPoint)
	{
		WorldMap worldMap = client.getWorldMap();

		float pixelsPerTile = worldMap.getWorldMapZoom();

		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (mapProjectionUsable(map))
		{
			Rectangle worldMapRect = map.getBounds();

			int heightInTiles = (int) Math.ceil(worldMapRect.getHeight() / pixelsPerTile);

			Point worldMapPosition = worldMap.getWorldMapPosition();

			int yTileMax = worldMapPosition.getY() - heightInTiles / 2;
			int yTileOffset = (yTileMax - WorldPointUtil.unpackWorldY(packedWorldPoint) - 1) * -1;

			int yGraphDiff = (int) (yTileOffset * pixelsPerTile);
			yGraphDiff -= (int) (pixelsPerTile - Math.ceil(pixelsPerTile / 2));
			yGraphDiff = worldMapRect.height - yGraphDiff;
			yGraphDiff += (int) worldMapRect.getY();

			return yGraphDiff;
		}
		return Integer.MIN_VALUE;
	}

	private void addMenuEntry(MenuEntryAdded event, String option, String target, int position)
	{
		List<MenuEntry> entries = new LinkedList<>(Arrays.asList(client.getMenu().getMenuEntries()));

		if (entries.stream().anyMatch(e -> e.getOption().equals(option) && e.getTarget().equals(target)))
		{
			return;
		}

		client.getMenu().createMenuEntry(position)
			.setOption(option)
			.setTarget(target)
			.setParam0(event.getActionParam0())
			.setParam1(event.getActionParam1())
			.setIdentifier(event.getIdentifier())
			.setType(MenuAction.RUNELITE)
			.onClick(this::onMenuOptionClicked);
	}

	/**
	 * kewl: the shim's own resolver instead of upstream's three-way guess, and this is what makes the
	 * minimap drawing FOLLOW THE MINIMAP -- the thing the user actually asked for.
	 *
	 * <p>Two differences, both of which the upstream form gets wrong on this client:</p>
	 * <ul>
	 *   <li>It picks the top-level interface by ASKING which one resolved, rather than by branching on
	 *       {@code isResized()} and a varbit. All three of these widgets exist in the tree; only the
	 *       built one has a rectangle, and choosing wrong hands back a stale or empty one.</li>
	 *   <li>It returns null unless the rectangle is CANVAS-ABSOLUTE. Every consumer of this widget --
	 *       {@link #getMinimapClipArea}, its simple fallback, {@code Perspective.localToMinimap} --
	 *       uses the bounds as a clip or an origin, and a parent-relative rectangle is the failure
	 *       that piled every minimap dot in the canvas's top-left corner.</li>
	 * </ul>
	 *
	 * <p>Nothing here caches the rectangle: it is re-read every frame (the shim memoises the native
	 * call per frame), so moving or resizing the interface moves the drawing with it on the next
	 * frame, and {@link #getMinimapClipArea} already rebuilds its clip whenever the bounds change.</p>
	 */
	private Widget getMinimapDrawWidget()
	{
		return client.getMinimapDrawWidget();
	}

	private Shape getMinimapClipAreaSimple()
	{
		Widget minimapDrawArea = getMinimapDrawWidget();

		if (minimapDrawArea == null || minimapDrawArea.isHidden())
		{
			return null;
		}

		Rectangle bounds = minimapDrawArea.getBounds();

		return new Ellipse2D.Double(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight());
	}

	public Shape getMinimapClipArea()
	{
		Widget minimapWidget = getMinimapDrawWidget();

		if (minimapWidget == null || minimapWidget.isHidden() || !minimapRectangle.equals(minimapRectangle = minimapWidget.getBounds()))
		{
			minimapClipFixed = null;
			minimapClipResizeable = null;
			minimapSpriteFixed = null;
			minimapSpriteResizeable = null;
		}

		if (minimapWidget == null || minimapWidget.isHidden())
		{
			return null;
		}

		if (client.isResized())
		{
			if (minimapClipResizeable != null)
			{
				return minimapClipResizeable;
			}
			if (minimapSpriteResizeable == null)
			{
				minimapSpriteResizeable = spriteManager.getSprite(SpriteID.RESIZE_MAP_MASK, 0);
			}
			if (minimapSpriteResizeable != null)
			{
				minimapClipResizeable = bufferedImageToPolygon(minimapSpriteResizeable);
				return minimapClipResizeable;
			}
			return getMinimapClipAreaSimple();
		}
		if (minimapClipFixed != null)
		{
			return minimapClipFixed;
		}
		if (minimapSpriteFixed == null)
		{
			minimapSpriteFixed = spriteManager.getSprite(SpriteID.FIXED_MAP_MASK, 0);
		}
		if (minimapSpriteFixed != null)
		{
			minimapClipFixed = bufferedImageToPolygon(minimapSpriteFixed);
			return minimapClipFixed;
		}
		return getMinimapClipAreaSimple();
	}

	private Polygon bufferedImageToPolygon(BufferedImage image)
	{
		Color outsideColour = null;
		Color previousColour;
		final int width = image.getWidth();
		final int height = image.getHeight();
		List<java.awt.Point> points = new ArrayList<>();
		for (int y = 0; y < height; y++)
		{
			previousColour = outsideColour;
			for (int x = 0; x < width; x++)
			{
				int rgb = image.getRGB(x, y);
				int a = (rgb & 0xff000000) >>> 24;
				int r = (rgb & 0x00ff0000) >> 16;
				int g = (rgb & 0x0000ff00) >> 8;
				int b = (rgb & 0x000000ff);
				Color colour = new Color(r, g, b, a);
				if (x == 0 && y == 0)
				{
					outsideColour = colour;
					previousColour = colour;
				}
				if (!colour.equals(outsideColour) && previousColour.equals(outsideColour))
				{
					points.add(new java.awt.Point(x, y));
				}
				if ((colour.equals(outsideColour) || x == (width - 1)) && !previousColour.equals(outsideColour))
				{
					points.add(0, new java.awt.Point(x, y));
				}
				previousColour = colour;
			}
		}
		int offsetX = minimapRectangle.x;
		int offsetY = minimapRectangle.y;
		Polygon polygon = new Polygon();
		for (java.awt.Point point : points)
		{
			polygon.addPoint(point.x + offsetX, point.y + offsetY);
		}
		return polygon;
	}
}
