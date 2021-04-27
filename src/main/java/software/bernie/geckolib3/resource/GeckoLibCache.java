package software.bernie.geckolib3.resource;

import com.eliotlash.molang.MolangParser;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceReloader;
import net.minecraft.util.Identifier;
import net.minecraft.util.profiler.Profiler;
import software.bernie.geckolib3.GeckoLib;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.file.AnimationFileLoader;
import software.bernie.geckolib3.file.GeoModelLoader;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.molang.MolangRegistrar;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Function;

public class GeckoLibCache {
	private Map<Identifier, AnimationFile> animations = Collections.emptyMap();
	private Map<Identifier, GeoModel> geoModels = Collections.emptyMap();
	private static GeckoLibCache INSTANCE;
	public final MolangParser parser = new MolangParser();
	private final AnimationFileLoader animationLoader;
	private final GeoModelLoader modelLoader;

	public Map<Identifier, AnimationFile> getAnimations() {
		if (!GeckoLib.hasInitialized) {
			throw new RuntimeException("GeckoLib was never initialized! Please read the documentation!");
		}
		return animations;
	}

	public Map<Identifier, GeoModel> getGeoModels() {
		if (!GeckoLib.hasInitialized) {
			throw new RuntimeException("GeckoLib was never initialized! Please read the documentation!");
		}
		return geoModels;
	}

	protected GeckoLibCache() {
		this.animationLoader = new AnimationFileLoader();
		this.modelLoader = new GeoModelLoader();
		MolangRegistrar.registerVars(parser);
	}

	public static GeckoLibCache getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new GeckoLibCache();
			return INSTANCE;
		}
		return INSTANCE;
	}

	public CompletableFuture<Void> reload(ResourceReloader.Synchronizer stage, ResourceManager resourceManager,
                                          Profiler preparationsProfiler, Profiler reloadProfiler, Executor backgroundExecutor,
                                          Executor gameExecutor) {
		Map<Identifier, AnimationFile> animations = new HashMap<>();
		Map<Identifier, GeoModel> geoModels = new HashMap<>();
		return CompletableFuture.allOf(loadResources(backgroundExecutor, resourceManager, "animations",
				animation -> animationLoader.loadAllAnimations(parser, animation, resourceManager), animations::put),
				loadResources(backgroundExecutor, resourceManager, "geo",
						resource -> modelLoader.loadModel(resourceManager, resource), geoModels::put))
				.thenCompose(stage::whenPrepared).thenAcceptAsync(empty -> {
					this.animations = animations;
					this.geoModels = geoModels;
				}, gameExecutor);
	}

	private static <T> CompletableFuture<Void> loadResources(Executor executor, ResourceManager resourceManager,
			String type, Function<Identifier, T> loader, BiConsumer<Identifier, T> map) {
		return CompletableFuture
				.supplyAsync(() -> resourceManager.findResources(type, fileName -> fileName.endsWith(".json")),
						executor)
				.thenApplyAsync(resources -> {
					Map<Identifier, CompletableFuture<T>> tasks = new HashMap<>();

					for (Identifier resource : resources) {
						CompletableFuture<T> existing = tasks.put(resource,
								CompletableFuture.supplyAsync(() -> loader.apply(resource), executor));

						if (existing != null) {// Possibly if this matters, the last one will win
							System.err.println("Duplicate resource for " + resource);
							existing.cancel(false);
						}
					}

					return tasks;
				}, executor).thenAcceptAsync(tasks -> {
					for (Entry<Identifier, CompletableFuture<T>> entry : tasks.entrySet()) {
						// Shouldn't be any duplicates as they are caught above
						map.accept(entry.getKey(), entry.getValue().join());
					}
				}, executor);
	}
}