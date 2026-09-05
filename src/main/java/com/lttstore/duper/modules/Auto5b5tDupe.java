package com.lttstore.duper.modules;

import com.lttstore.duper.DuperAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.TrappedChestBlock;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.CraftRequestC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeMatcher;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.minecraft.item.Items.CRAFTING_TABLE;
import static net.minecraft.item.Items.STICK;

public class Auto5b5tDupe extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgChest = settings.createGroup("Auto Chest");

    // --- General Settings ---
    private final Setting<DupeMode> dupeMode = sgGeneral.add(new EnumSetting.Builder<DupeMode>()
        .name("mode")
        .description("How to choose items to dupe.")
        .defaultValue(DupeMode.TargetItems)
        .build()
    );

    private final Setting<List<Item>> itemsToDupe = sgGeneral.add(new ItemListSetting.Builder()
        .name("items")
        .description("Specific items from inventory to automatically dupe.")
        .visible(() -> dupeMode.get() == DupeMode.TargetItems)
        .build()
    );

    private final Setting<Recipe> recipeMode = sgGeneral.add(new EnumSetting.Builder<Recipe>()
        .name("recipe")
        .description("Recipe to craft for desync. Ensure you have planks in inventory.")
        .defaultValue(Recipe.Stick)
        .build()
    );

    private final Setting<Boolean> autoRepeat = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-repeat")
        .description("Continuously repeats the dupe process automatically.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay in ticks between dupe cycles (allows picking up items).")
        .defaultValue(4)
        .min(1)
        .max(40)
        .sliderRange(1, 20)
        .visible(autoRepeat::get)
        .build()
    );

    private final Setting<Boolean> dropAll = sgGeneral.add(new BoolSetting.Builder()
        .name("drop-all")
        .description("Drop entire stack instead of single item.")
        .defaultValue(false)
        .build()
    );

    private final Setting<RotationMode> rotationMode = sgGeneral.add(new EnumSetting.Builder<RotationMode>()
        .name("rotation-mode")
        .description("Rotation mode when dropping items.")
        .defaultValue(RotationMode.Silent)
        .build()
    );

    private final Setting<Boolean> single = sgGeneral.add(new BoolSetting.Builder()
        .name("single-instant")
        .description("Only send craft desync packet once without dropping.")
        .defaultValue(false)
        .build()
    );

    // --- Auto Chest Settings ---
    private final Setting<Boolean> autoStore = sgChest.add(new BoolSetting.Builder()
        .name("auto-store")
        .description("Deposit duped items into a nearby chest when inventory gets full.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> chestRange = sgChest.add(new IntSetting.Builder()
        .name("chest-range")
        .description("Radius in blocks to search for chests, barrels, or shulkers.")
        .defaultValue(4)
        .min(1)
        .max(6)
        .sliderRange(1, 6)
        .visible(autoStore::get)
        .build()
    );

    private final Setting<Integer> keepStacks = sgChest.add(new IntSetting.Builder()
        .name("keep-stacks")
        .description("How many stacks of the duped item to keep in inventory to continue duping.")
        .defaultValue(1)
        .min(1)
        .max(5)
        .sliderRange(1, 5)
        .visible(autoStore::get)
        .build()
    );

    private final Setting<Integer> emptySlotsThreshold = sgChest.add(new IntSetting.Builder()
        .name("empty-slots-threshold")
        .description("Store in chest when empty inventory slots fall below or reach this number.")
        .defaultValue(2)
        .min(0)
        .max(8)
        .sliderRange(0, 8)
        .visible(autoStore::get)
        .build()
    );

    // --- State Variables ---
    private Phase phase = Phase.PREPARE;
    private int timer = 0;
    private int chestTimeout = 0;
    private RecipeMatcher recipeFinder;
    private RecipeEntry<?> stickRecipe;
    private float oldPitch;
    private boolean pitchChanged = false;
    private Item lastDupedItem = null;

    public Auto5b5tDupe() {
        super(DuperAddon.CATEGORY, "auto-5b5t-dupe", "Automatically dupes multiple items with chest storage on 5b5t.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) {
            toggle();
            return;
        }

        recipeFinder = new RecipeMatcher();
        pitchChanged = false;
        timer = 0;
        chestTimeout = 0;
        lastDupedItem = null;

        if (single.get()) {
            mc.player.getInventory().populateRecipeFinder(recipeFinder);
            if (!placeRecipe(recipeFinder)) {
                toggle();
                return;
            }
            mc.player.networkHandler.sendPacket(new CraftRequestC2SPacket(mc.player.currentScreenHandler.syncId, stickRecipe, false));
            toggle();
            return;
        }

        phase = Phase.PREPARE;
    }

    @Override
    public void onDeactivate() {
        unrotate();
        phase = Phase.PREPARE;
        timer = 0;
    }

    @EventHandler
    private void onPostTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) {
            toggle();
            return;
        }

        if (timer > 0) {
            timer--;
            return;
        }

        switch (phase) {
            case PREPARE -> {
                // Check if inventory is full and autoStore is enabled
                if (autoStore.get() && getEmptySlotsCount() <= emptySlotsThreshold.get()) {
                    BlockPos chestPos = findNearbyChest(chestRange.get());
                    if (chestPos != null) {
                        BlockHitResult hitResult = new BlockHitResult(Vec3d.ofCenter(chestPos), Direction.UP, chestPos, false);
                        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
                        phase = Phase.OPEN_CHEST;
                        chestTimeout = 25;
                        timer = 2;
                        return;
                    } else {
                        ChatUtils.warning("Inventory is full and no nearby chest was found!");
                        toggle();
                        return;
                    }
                }

                // Verify recipe ingredients
                mc.player.getInventory().populateRecipeFinder(recipeFinder);
                if (!placeRecipe(recipeFinder)) {
                    toggle();
                    return;
                }

                // Select next item to dupe according to mode
                if (!selectItemToDupe()) {
                    toggle();
                    return;
                }

                // Apply rotation
                rotate();

                phase = Phase.DROP;
                timer = 1;
            }

            case DROP -> {
                ItemStack held = mc.player.getMainHandStack();
                if (held.isEmpty()) {
                    unrotate();
                    phase = Phase.PREPARE;
                    return;
                }

                lastDupedItem = held.getItem();
                mc.player.dropSelectedItem(dropAll.get());
                phase = Phase.CRAFT;
                timer = 1;
            }

            case CRAFT -> {
                unrotate();
                mc.player.networkHandler.sendPacket(new CraftRequestC2SPacket(mc.player.currentScreenHandler.syncId, stickRecipe, false));

                if (!autoRepeat.get()) {
                    toggle();
                    return;
                }

                phase = Phase.WAIT_PICKUP;
                timer = delay.get();
            }

            case WAIT_PICKUP -> {
                // Transition back to prepare for next cycle
                phase = Phase.PREPARE;
            }

            case OPEN_CHEST -> {
                if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler || mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler) {
                    phase = Phase.DEPOSIT_CHEST;
                    timer = 2;
                    return;
                }

                if (chestTimeout-- <= 0) {
                    ChatUtils.warning("Failed to open container in time.");
                    phase = Phase.PREPARE;
                    timer = delay.get();
                }
            }

            case DEPOSIT_CHEST -> {
                depositToContainer();
                mc.player.closeHandledScreen();
                ChatUtils.info("Deposited items into chest. Resuming dupe...");
                phase = Phase.PREPARE;
                timer = delay.get();
            }
        }
    }

    private boolean selectItemToDupe() {
        switch (dupeMode.get()) {
            case HeldItem -> {
                if (mc.player.getMainHandStack().isEmpty()) {
                    ChatUtils.error("Hold an item to dupe in your hand.");
                    return false;
                }
                return true;
            }

            case TargetItems -> {
                List<Item> targetList = itemsToDupe.get();
                if (targetList == null || targetList.isEmpty()) {
                    ChatUtils.error("No items selected to dupe in settings.");
                    return false;
                }

                // Look for an item from target list in inventory
                int slot = findItemSlot(targetList);
                if (slot == -1) {
                    ChatUtils.warning("None of the target items were found in your inventory.");
                    return false;
                }

                return selectSlot(slot);
            }

            case AllInventory -> {
                // Find any item that isn't empty and isn't a crafting ingredient
                int slot = findAnyDupeableSlot();
                if (slot == -1) {
                    ChatUtils.warning("No dupeable items found in inventory.");
                    return false;
                }

                return selectSlot(slot);
            }
        }
        return false;
    }

    private int findItemSlot(List<Item> targets) {
        // Check hotbar first (0-8)
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && targets.contains(stack.getItem())) {
                return i;
            }
        }
        // Then main inventory (9-35)
        for (int i = 9; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && targets.contains(stack.getItem())) {
                return i;
            }
        }
        return -1;
    }

    private int findAnyDupeableSlot() {
        // Check hotbar first
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && !isCraftIngredient(stack)) {
                return i;
            }
        }
        // Check main inventory
        for (int i = 9; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && !isCraftIngredient(stack)) {
                return i;
            }
        }
        return -1;
    }

    private boolean selectSlot(int invSlot) {
        if (invSlot < 9) {
            mc.player.getInventory().selectedSlot = invSlot;
            return true;
        }

        // Swap from main inventory slot (9-35) to current hotbar slot
        int currentHotbar = mc.player.getInventory().selectedSlot;
        mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, invSlot, currentHotbar, SlotActionType.SWAP, mc.player);
        return true;
    }

    private boolean isCraftIngredient(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.isIn(ItemTags.PLANKS) || stack.isIn(ItemTags.LOGS)) return true;
        Item item = stack.getItem();
        return item == STICK || item == CRAFTING_TABLE;
    }

    private int getEmptySlotsCount() {
        if (mc.player == null) return 0;
        int empty = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) {
                empty++;
            }
        }
        return empty;
    }

    private BlockPos findNearbyChest(int range) {
        if (mc.player == null || mc.world == null) return null;
        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos bestPos = null;
        double bestDist = Double.MAX_VALUE;

        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    BlockState state = mc.world.getBlockState(pos);
                    Block block = state.getBlock();
                    if (block instanceof ChestBlock || block instanceof TrappedChestBlock
                        || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock) {
                        double dist = mc.player.squaredDistanceTo(Vec3d.ofCenter(pos));
                        if (dist < bestDist) {
                            bestDist = dist;
                            bestPos = pos;
                        }
                    }
                }
            }
        }
        return bestPos;
    }

    private void depositToContainer() {
        if (mc.player == null || mc.player.currentScreenHandler == null) return;

        var handler = mc.player.currentScreenHandler;

        // Count how many stacks of each deposit-eligible item the player currently holds
        Map<Item, Integer> stackCounts = new HashMap<>();
        for (Slot slot : handler.slots) {
            if (slot.inventory == mc.player.getInventory() && !slot.getStack().isEmpty()) {
                ItemStack stack = slot.getStack();
                if (shouldDeposit(stack)) {
                    stackCounts.put(stack.getItem(), stackCounts.getOrDefault(stack.getItem(), 0) + 1);
                }
            }
        }

        // Determine how many excess stacks of each item can be deposited
        Map<Item, Integer> excessToDeposit = new HashMap<>();
        for (Map.Entry<Item, Integer> entry : stackCounts.entrySet()) {
            int excess = entry.getValue() - keepStacks.get();
            if (excess > 0) {
                excessToDeposit.put(entry.getKey(), excess);
            }
        }

        // Transfer excess stacks to container via QUICK_MOVE (shift-click)
        for (Slot slot : handler.slots) {
            if (slot.inventory == mc.player.getInventory() && !slot.getStack().isEmpty()) {
                ItemStack stack = slot.getStack();
                Item item = stack.getItem();
                int remaining = excessToDeposit.getOrDefault(item, 0);
                if (remaining > 0) {
                    mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, mc.player);
                    excessToDeposit.put(item, remaining - 1);
                }
            }
        }
    }

    private boolean shouldDeposit(ItemStack stack) {
        if (isCraftIngredient(stack)) return false;

        return switch (dupeMode.get()) {
            case TargetItems -> itemsToDupe.get() != null && itemsToDupe.get().contains(stack.getItem());
            case AllInventory -> true;
            case HeldItem -> lastDupedItem != null && stack.getItem() == lastDupedItem;
        };
    }

    private void rotate() {
        switch (rotationMode.get()) {
            case Silent -> mc.world.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(mc.player.headYaw, 90f, true));
            case Client -> {
                oldPitch = mc.player.getPitch();
                pitchChanged = true;
                mc.player.setPitch(90f);
            }
            case None -> {}
        }
    }

    private void unrotate() {
        if (pitchChanged && rotationMode.get() == RotationMode.Client) {
            mc.player.setPitch(oldPitch);
            pitchChanged = false;
        }
    }

    boolean placeRecipe(RecipeMatcher recipeFinder) {
        List<RecipeResultCollection> recipeList = mc.player.getRecipeBook().getOrderedResults();
        for (RecipeResultCollection recipe : recipeList) {
            for (RecipeEntry<?> entry : recipe.getAllRecipes()) {
                ItemStack resultStack = entry.value().getResult(mc.world.getRegistryManager());
                if (resultStack.getItem() == recipeMode.get().item) {
                    if (!recipeFinder.match(entry.value(), null)) {
                        ChatUtils.error("No ingredients in inventory for " + recipeMode.get().item.getName().getString());
                        return false;
                    }
                    stickRecipe = entry;
                    return true;
                }
            }
        }
        return false;
    }

    public enum DupeMode {
        TargetItems,
        AllInventory,
        HeldItem
    }

    private enum Phase {
        PREPARE,
        DROP,
        CRAFT,
        WAIT_PICKUP,
        OPEN_CHEST,
        DEPOSIT_CHEST
    }

    public enum RotationMode {
        Silent,
        Client,
        None
    }

    public enum Recipe {
        Stick(STICK),
        CraftingTable(CRAFTING_TABLE);

        final Item item;

        Recipe(Item item) {
            this.item = item;
        }
    }
}

