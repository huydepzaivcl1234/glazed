package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AncientDebrisESP extends Module {
    private final SettingGroup sgGeneral = settings.createGroup("General");

    private final Setting<SettingColor> debrisColor = sgGeneral.add(new ColorSetting.Builder()
        .name("debris-color")
        .description("Ancient debris box color")
        .defaultValue(new SettingColor(255, 100, 0, 100))
        .build());

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Ancient debris box render mode")
        .defaultValue(ShapeMode.Both)
        .build());

    private final Setting<Boolean> tracers = sgGeneral.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw tracers to ancient debris blocks")
        .defaultValue(true)
        .build());

    private final Setting<SettingColor> tracerColor = sgGeneral.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Ancient debris tracer color")
        .defaultValue(new SettingColor(255, 100, 0, 200))
        .visible(tracers::get)
        .build());

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Announce ancient debris in chat")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> soundAlert = sgGeneral.add(new BoolSetting.Builder()
        .name("sound-alert")
        .description("Play sound when ancient debris is found")
        .defaultValue(true)
        .build());

    private final SettingGroup sgFiltering = settings.createGroup("Filtering");

    private final Setting<Integer> minY = sgFiltering.add(new IntSetting.Builder()
        .name("min-y")
        .description("Minimum Y level to scan for ancient debris")
        .defaultValue(8)
        .min(-64)
        .max(128)
        .sliderRange(-64, 128)
        .build());

    private final Setting<Integer> maxY = sgFiltering.add(new IntSetting.Builder()
        .name("max-y")
        .description("Maximum Y level to scan for ancient debris")
        .defaultValue(120)
        .min(-64)
        .max(320)
        .sliderRange(-64, 320)
        .build());

    private final Setting<Boolean> onlyNether = sgFiltering.add(new BoolSetting.Builder()
        .name("only-nether")
        .description("Only show ancient debris in the nether dimension")
        .defaultValue(true)
        .build());

    private final SettingGroup sgThreading = settings.createGroup("Threading");

    private final Setting<Boolean> useThreading = sgThreading.add(new BoolSetting.Builder()
        .name("enable-threading")
        .description("Use multi-threading for chunk scanning (better performance)")
        .defaultValue(true)
        .build());

    private final Setting<Integer> threadPoolSize = sgThreading.add(new IntSetting.Builder()
        .name("thread-pool-size")
        .description("Number of threads to use for scanning")
        .defaultValue(3)
        .min(1)
        .max(8)
        .sliderRange(1, 8)
        .visible(useThreading::get)
        .build());

    private final Setting<Boolean> limitChatSpam = sgThreading.add(new BoolSetting.Builder()
        .name("limit-chat-spam")
        .description("Reduce chat spam when using threading")
        .defaultValue(true)
        .visible(useThreading::get)
        .build());

    private final Set<BlockPos> debrisPositions = ConcurrentHashMap.newKeySet();
    private ExecutorService threadPool;
    private long lastAlertTime = 0;
    private static final long ALERT_COOLDOWN = 1000;

    public AncientDebrisESP() {
        super(GlazedAddon.esp, "ancient-debris-esp", "ESP for ancient debris in the nether. Bypasses anti-cheat by scanning loaded chunks only.");
    }

    @Override
    public void onActivate() {
        if (mc.world == null) return;

        if (onlyNether.get() && !mc.world.getDimensionKey().getValue().getPath().equals("the_nether")) {
            warning("Ancient Debris ESP only works in the Nether!");
            toggle();
            return;
        }

        if (useThreading.get()) {
            threadPool = Executors.newFixedThreadPool(threadPoolSize.get());
        }

        debrisPositions.clear();

        if (useThreading.get()) {
            for (Chunk chunk : Utils.chunks()) {
                if (chunk instanceof WorldChunk worldChunk) {
                    threadPool.submit(() -> scanChunk(worldChunk));
                }
            }
        } else {
            for (Chunk chunk : Utils.chunks()) {
                if (chunk instanceof WorldChunk worldChunk) scanChunk(worldChunk);
            }
        }

        info("Ancient Debris ESP activated. Scanning chunks...");
    }

    @Override
    public void onDeactivate() {
        if (threadPool != null && !threadPool.isShutdown()) {
            threadPool.shutdown();
            threadPool = null;
        }

        debrisPositions.clear();
    }

    @EventHandler
    private void onChunkLoad(ChunkDataEvent event) {
        if (useThreading.get() && threadPool != null && !threadPool.isShutdown()) {
            threadPool.submit(() -> scanChunk(event.chunk()));
        } else {
            scanChunk(event.chunk());
        }
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        BlockPos pos = event.pos;
        BlockState state = event.newState;

        Runnable updateTask = () -> {
            if (isAncientDebrisInRange(state, pos.getY())) {
                boolean wasAdded = debrisPositions.add(pos);
                if (wasAdded && chatFeedback.get() && (!useThreading.get() || !limitChatSpam.get())) {
                    info("Ancient Debris found at " + pos.toShortString());
                    if (soundAlert.get()) {
                        playAlert();
                    }
                }
            } else {
                debrisPositions.remove(pos);
            }
        };

        if (useThreading.get() && threadPool != null && !threadPool.isShutdown()) {
            threadPool.submit(updateTask);
        } else {
            updateTask.run();
        }
    }

    private void scanChunk(WorldChunk chunk) {
        ChunkPos cpos = chunk.getPos();
        int xStart = cpos.getStartX();
        int zStart = cpos.getStartZ();
        int yMin = Math.max(chunk.getBottomY(), minY.get());
        int yMax = Math.min(chunk.getBottomY() + chunk.getHeight(), maxY.get());

        Set<BlockPos> chunkDebris = new HashSet<>();
        int foundCount = 0;

        for (int x = xStart; x < xStart + 16; x++) {
            for (int z = zStart; z < zStart + 16; z++) {
                for (int y = yMin; y < yMax; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (isAncientDebrisInRange(chunk.getBlockState(pos), y)) {
                        chunkDebris.add(pos);
                        foundCount++;
                    }
                }
            }
        }

        debrisPositions.removeIf(pos -> {
            ChunkPos blockChunk = new ChunkPos(pos);
            return blockChunk.equals(cpos) && !chunkDebris.contains(pos);
        });

        int newBlocks = 0;
        for (BlockPos pos : chunkDebris) {
            if (debrisPositions.add(pos)) {
                newBlocks++;
            }
        }

        if (chatFeedback.get() && foundCount > 0) {
            if (useThreading.get() && limitChatSpam.get()) {
                if (newBlocks > 0) {
                    info("Chunk " + cpos.x + "," + cpos.z + ": " + newBlocks + " ancient debris found");
                    if (soundAlert.get()) {
                        playAlert();
                    }
                }
            } else {
                for (BlockPos pos : chunkDebris) {
                    if (!debrisPositions.contains(pos)) {
                        info("Ancient Debris at " + pos.toShortString());
                        if (soundAlert.get()) {
                            playAlert();
                        }
                    }
                }
            }
        }
    }

    private void playAlert() {
        long now = System.currentTimeMillis();
        if (now - lastAlertTime > ALERT_COOLDOWN) {
            lastAlertTime = now;
            if (mc.player != null) {
                mc.player.playSound(net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0F, 1.0F);
            }
        }
    }

    private boolean isAncientDebrisInRange(BlockState state, int y) {
        return y >= minY.get() && y <= maxY.get() && state.getBlock() == Blocks.ANCIENT_DEBRIS;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;

        Vec3d playerPos = mc.player.getLerpedPos(event.tickDelta);
        Color side = new Color(debrisColor.get());
        Color outline = new Color(debrisColor.get());
        Color tracerColorValue = new Color(tracerColor.get());

        for (BlockPos pos : debrisPositions) {
            event.renderer.box(pos, side, outline, shapeMode.get(), 0);

            if (tracers.get()) {
                Vec3d blockCenter = Vec3d.ofCenter(pos);

                Vec3d startPos;
                if (mc.options.getPerspective().isFirstPerson()) {
                    Vec3d lookDirection = mc.player.getRotationVector();
                    startPos = new Vec3d(
                        playerPos.x + lookDirection.x * 0.5,
                        playerPos.y + mc.player.getEyeHeight(mc.player.getPose()) + lookDirection.y * 0.5,
                        playerPos.z + lookDirection.z * 0.5
                    );
                } else {
                    startPos = new Vec3d(
                        playerPos.x,
                        playerPos.y + mc.player.getEyeHeight(mc.player.getPose()),
                        playerPos.z
                    );
                }

                event.renderer.line(startPos.x, startPos.y, startPos.z,
                    blockCenter.x, blockCenter.y, blockCenter.z, tracerColorValue);
            }
        }
    }
}