/*******************************************************************************
 * HellFirePvP / Modular Machinery 2019
 *
 * This project is licensed under GNU GENERAL PUBLIC LICENSE Version 3.
 * The source code is available on github: https://github.com/HellFirePvP/ModularMachinery
 * For further details, see the License file there.
 ******************************************************************************/

package hellfirepvp.modularmachinery.common.tiles;

import crafttweaker.util.IEventHandler;
import github.kasuminova.mmce.common.concurrent.RecipeSearchTask;
import github.kasuminova.mmce.common.concurrent.Sync;
import hellfirepvp.modularmachinery.ModularMachinery;
import hellfirepvp.modularmachinery.common.block.BlockController;
import hellfirepvp.modularmachinery.common.crafting.ActiveMachineRecipe;
import hellfirepvp.modularmachinery.common.crafting.MachineRecipe;
import hellfirepvp.modularmachinery.common.crafting.RecipeRegistry;
import hellfirepvp.modularmachinery.common.crafting.helper.CraftingStatus;
import hellfirepvp.modularmachinery.common.crafting.helper.RecipeCraftingContext;
import hellfirepvp.modularmachinery.common.integration.crafttweaker.event.recipe.*;
import hellfirepvp.modularmachinery.common.lib.BlocksMM;
import hellfirepvp.modularmachinery.common.machine.DynamicMachine;
import hellfirepvp.modularmachinery.common.machine.MachineRegistry;
import hellfirepvp.modularmachinery.common.modifier.RecipeModifier;
import hellfirepvp.modularmachinery.common.tiles.base.TileMultiblockMachineController;
import hellfirepvp.modularmachinery.common.util.BlockArrayCache;
import hellfirepvp.modularmachinery.common.util.MiscUtils;
import io.netty.util.internal.ThrowableUtil;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.ForkJoinPool;

/**
 * <p>完全重构的社区版机械控制器，拥有强大的异步逻辑和极低的性能消耗。</p>
 * <p>Completely refactored community edition mechanical controller with powerful asynchronous logic and extremely low performance consumption.</p>
 * TODO: This class is too large, consider improving readability.
 */
public class TileMachineController extends TileMultiblockMachineController {
    private BlockController parentController = null;
    private ActiveMachineRecipe activeRecipe = null;
    private RecipeCraftingContext context = null;
    private RecipeSearchTask searchTask = null;

    public TileMachineController() {
    }

    public TileMachineController(IBlockState state) {
        this();
        if (state.getBlock() instanceof BlockController) {
            this.parentController = (BlockController) state.getBlock();
            this.parentMachine = parentController.getParentMachine();
            this.controllerRotation = state.getValue(BlockController.FACING);
        } else {
            // wtf, where is the controller?
            ModularMachinery.log.warn("Invalid controller block at " + getPos() + " !");
            controllerRotation = EnumFacing.NORTH;
        }
    }

    @Override
    public void doControllerTick() {
        if (getWorld().getStrongPower(getPos()) > 0) {
            return;
        }

        // Use async check for large structure
        if (isStructureFormed() && !ModularMachinery.pluginServerCompatibleMode && this.foundPattern.getPattern().size() >= 1000) {
            tickExecutor = ModularMachinery.EXECUTE_MANAGER.addParallelAsyncTask(() -> {
                if (!doStructureCheck() || !isStructureFormed()) {
                    return;
                }
                onMachineTick();
                if (activeRecipe != null || searchAndStartRecipe()) {
                    doRecipeTick();
                }
            }, usedTimeAvg());
            return;
        }

        // Normal logic
        if (!doStructureCheck() || !isStructureFormed()) {
            return;
        }

        tickExecutor = ModularMachinery.EXECUTE_MANAGER.addParallelAsyncTask(() -> {
            onMachineTick();
            if (activeRecipe != null || searchAndStartRecipe()) {
                doRecipeTick();
            }
        }, usedTimeAvg());
    }

    @Override
    protected void checkRotation() {
        IBlockState state = getWorld().getBlockState(getPos());
        if (state.getBlock() instanceof BlockController) {
            this.parentController = (BlockController) state.getBlock();
            this.parentMachine = parentController.getParentMachine();
            this.controllerRotation = state.getValue(BlockController.FACING);
        } else {
            // wtf, where is the controller?
            ModularMachinery.log.warn("Invalid controller block at " + getPos() + " !");
            controllerRotation = EnumFacing.NORTH;
        }
    }

    private void doRecipeTick() {
        resetStructureCheckCounter();

        if (this.context == null) {
            //context preInit
            context = createContext(this.activeRecipe);
            context.setParallelism(this.activeRecipe.getParallelism());
        }

        CraftingStatus prevStatus = this.getCraftingStatus();

        //检查预 Tick 事件是否阻止进一步运行。
        //Check if the PreTick event prevents further runs.
        if (!onPreTick()) {
            if (this.activeRecipe != null) {
                this.activeRecipe.tick(this, this.context);
                if (this.getCraftingStatus().isCrafting()) {
                    this.activeRecipe.setTick(Math.max(this.activeRecipe.getTick() - 1, 0));
                }
            }
            markForUpdateSync();
            return;
        }

        //当脚本修改了运行状态时，内部不再覆盖运行状态。
        //When scripts changed craftingStatus, it is no longer modified internally.
        if (prevStatus.equals(this.getCraftingStatus())) {
            this.craftingStatus = this.activeRecipe.tick(this, this.context);
        } else {
            this.activeRecipe.tick(this, this.context);
        }
        if (this.getCraftingStatus().isCrafting()) {
            onTick();
            if (activeRecipe != null && activeRecipe.isCompleted()) {
                if (ModularMachinery.pluginServerCompatibleMode) {
                    ModularMachinery.EXECUTE_MANAGER.addSyncTask(this::onFinished);
                } else {
                    onFinished();
                }
            }
        } else {
            boolean destruct = onFailure();
            if (destruct) {
                this.activeRecipe = null;
                this.context = null;
            }
        }
        markForUpdateSync();
    }

    @SuppressWarnings("unused")
    public BlockController getParentController() {
        return parentController;
    }

    @SuppressWarnings("unused")
    public DynamicMachine getParentMachine() {
        return parentMachine;
    }

    /**
     * <p>机器开始执行一个配方。</p>
     *
     * @param activeRecipe ActiveMachineRecipe
     * @param context      RecipeCraftingContext
     */
    public void onStart(ActiveMachineRecipe activeRecipe, RecipeCraftingContext context) {
        this.activeRecipe = activeRecipe;
        this.context = context;

        List<IEventHandler<RecipeEvent>> handlerList = this.activeRecipe.getRecipe().getRecipeEventHandlers(RecipeStartEvent.class);
        if (handlerList != null && !handlerList.isEmpty()) {
            RecipeStartEvent event = new RecipeStartEvent(this);
            for (IEventHandler<RecipeEvent> handler : handlerList) {
                handler.handle(event);
            }
        }
        activeRecipe.start(context);
    }

    /**
     * <p>机器在完成配方 Tick 后执行</p>
     *
     * @return 如果为 false，则进度停止增加，并在控制器状态栏输出原因
     */
    public boolean onPreTick() {
        List<IEventHandler<RecipeEvent>> handlerList = this.activeRecipe.getRecipe().getRecipeEventHandlers(RecipePreTickEvent.class);
        if (handlerList == null || handlerList.isEmpty()) return true;

        for (IEventHandler<RecipeEvent> handler : handlerList) {
            RecipePreTickEvent event = new RecipePreTickEvent(this);
            handler.handle(event);

            if (event.isPreventProgressing()) {
                setCraftingStatus(CraftingStatus.working(event.getReason()));
                return false;
            }
            if (event.isFailure()) {
                if (event.isDestructRecipe()) {
                    this.activeRecipe = null;
                    this.context = null;
                }
                setCraftingStatus(CraftingStatus.failure(event.getReason()));
                return false;
            }
        }

        return true;
    }

    /**
     * <p>与 {@code onPreTick()} 相似，但是可以销毁配方。</p>
     */
    public void onTick() {
        List<IEventHandler<RecipeEvent>> handlerList = this.activeRecipe.getRecipe().getRecipeEventHandlers(RecipeTickEvent.class);
        if (handlerList == null || handlerList.isEmpty()) return;

        for (IEventHandler<RecipeEvent> handler : handlerList) {
            RecipeTickEvent event = new RecipeTickEvent(this);
            handler.handle(event);
            if (event.isFailure()) {
                if (event.isDestructRecipe()) {
                    this.activeRecipe = null;
                    this.context = null;
                }
                setCraftingStatus(CraftingStatus.failure(event.getFailureReason()));
                return;
            }
        }
    }

    /**
     * <p>运行配方失败时（例如跳电）触发，可能会触发多次。</p>
     * @return true 为销毁配方（即为吞材料），false 则什么都不做。
     */
    public boolean onFailure() {
        MachineRecipe recipe = activeRecipe.getRecipe();
        boolean destruct = recipe.doesCancelRecipeOnPerTickFailure();

        List<IEventHandler<RecipeEvent>> handlerList = recipe.getRecipeEventHandlers(RecipeFailureEvent.class);
        if (handlerList == null || handlerList.isEmpty()) {
            return destruct;
        }

        RecipeFailureEvent event = new RecipeFailureEvent(
                this, craftingStatus.getUnlocMessage(), destruct);
        for (IEventHandler<RecipeEvent> handler : handlerList) {
            handler.handle(event);
        }

        return event.isDestructRecipe();
    }

    /**
     * <p>机械完成一个配方。</p>
     */
    public void onFinished() {
        if (this.activeRecipe == null) {
            ModularMachinery.log.warn("Machine " + MiscUtils.posToString(getPos()) + " Try to finish a null recipe! ignored.");
            this.context = null;
            this.activeRecipe = null;
            return;
        }
        MachineRecipe recipe = this.activeRecipe.getRecipe();
        List<IEventHandler<RecipeEvent>> handlerList = recipe.getRecipeEventHandlers(RecipeFinishEvent.class);
        if (handlerList != null && !handlerList.isEmpty()) {
            RecipeFinishEvent event = new RecipeFinishEvent(this);
            for (IEventHandler<RecipeEvent> handler : handlerList) {
                handler.handle(event);
            }
        }

        this.context.finishCrafting();

        this.activeRecipe.reset();
        this.activeRecipe.setMaxParallelism(isParallelized() ? getMaxParallelism() : 1);
        this.context = createContext(this.activeRecipe);
        tryStartRecipe(context);
    }

    /**
     * <p>机器尝试开始执行一个配方。</p>
     *
     * @param context RecipeCraftingContext
     */
    private void tryStartRecipe(@Nonnull RecipeCraftingContext context) {
        RecipeCraftingContext.CraftingCheckResult tryResult = onCheck(context);

        if (tryResult.isSuccess()) {
            Sync.doSyncAction(() -> onStart(context.getActiveRecipe(), context));
            markForUpdateSync();
        } else {
            this.craftingStatus = CraftingStatus.failure(tryResult.getFirstErrorMessage(""));
            this.activeRecipe = null;
            this.context = null;

            createRecipeSearchTask();
        }
    }

    @Override
    protected void checkAllPatterns() {
        for (DynamicMachine machine : MachineRegistry.getRegistry()) {
            if (machine.isRequiresBlueprint() || machine.isFactoryOnly()) continue;
            if (matchesRotation(
                    BlockArrayCache.getBlockArrayCache(machine.getPattern(), controllerRotation),
                    machine, controllerRotation))
            {
                onStructureFormed();
                break;
            }
        }
    }

    @Override
    public ActiveMachineRecipe getActiveRecipe() {
        return activeRecipe;
    }

    @Nullable
    @Override
    public ActiveMachineRecipe[] getActiveRecipeList() {
        return new ActiveMachineRecipe[]{activeRecipe};
    }

    @Override
    public boolean isWorking() {
        return getCraftingStatus().isCrafting();
    }

    public void cancelCrafting(String reason) {
        this.activeRecipe = null;
        this.context = null;
        this.craftingStatus = CraftingStatus.failure(reason);
    }

    @Override
    public void overrideStatusInfo(String newInfo) {
        this.getCraftingStatus().overrideStatusMessage(newInfo);
    }

    public void flushContextModifier() {
        if (context != null) {
            this.context.overrideModifier(MiscUtils.flatten(this.foundModifiers.values()));
            for (RecipeModifier modifier : customModifiers.values()) {
                this.context.addModifier(modifier);
            }
        }
    }

    @Override
    protected void onStructureFormed() {
        Sync.doSyncAction(() -> {
            if (parentController != null) {
                this.world.setBlockState(pos, parentController.getDefaultState().withProperty(BlockController.FACING, this.controllerRotation));
            } else {
                this.world.setBlockState(pos, BlocksMM.blockController.getDefaultState().withProperty(BlockController.FACING, this.controllerRotation));
            }
        });

        super.onStructureFormed();
    }

    @Override
    protected void resetMachine(boolean clearData) {
        super.resetMachine(clearData);
    }

    /**
     * 搜索并运行配方。
     *
     * @return 如果有配方正在运行，返回 true，否则 false。
     */
    private boolean searchAndStartRecipe() {
        if (searchTask != null) {
            if (!searchTask.isDone()) {
                return false;
            }

            //并发检查
            if (searchTask.getCurrentMachine() == getFoundMachine()) {
                try {
                    RecipeCraftingContext context = searchTask.get();
                    if (context != null) {
                        tryStartRecipe(context);
                        searchTask = null;
                        resetRecipeSearchRetryCount();
                        return true;
                    } else {
                        incrementRecipeSearchRetryCount();
                        CraftingStatus status = searchTask.getStatus();
                        if (status != null) {
                            this.craftingStatus = status;
                        }
                    }
                } catch (Exception e) {
                    ModularMachinery.log.warn(ThrowableUtil.stackTraceToString(e));
                }
            }

            searchTask = null;
        } else if (this.ticksExisted % currentRecipeSearchDelay() == 0) {
            createRecipeSearchTask();
        }
        return false;
    }

    private void createRecipeSearchTask() {
        searchTask = new RecipeSearchTask(this, getFoundMachine(), getMaxParallelism(), RecipeRegistry.getRecipesFor(foundMachine));
        ForkJoinPool.commonPool().submit(searchTask);
    }

    @Override
    public void readCustomNBT(NBTTagCompound compound) {
        super.readCustomNBT(compound);

        if (compound.hasKey("activeRecipe")) {
            NBTTagCompound tag = compound.getCompoundTag("activeRecipe");
            ActiveMachineRecipe recipe = new ActiveMachineRecipe(tag);
            if (recipe.getRecipe() == null) {
                ModularMachinery.log.info("Couldn't find recipe named " + tag.getString("recipeName") + " for controller at " + getPos());
                this.activeRecipe = null;
            } else {
                this.activeRecipe = recipe;
            }
        } else {
            this.activeRecipe = null;
        }
    }

    @Override
    protected void readMachineNBT(NBTTagCompound compound) {
        if (compound.hasKey("parentMachine")) {
            ResourceLocation rl = new ResourceLocation(compound.getString("parentMachine"));
            parentMachine = MachineRegistry.getRegistry().getMachine(rl);
            if (parentMachine != null) {
                parentController = BlockController.MACHINE_CONTROLLERS.get(parentMachine);
            } else {
                ModularMachinery.log.info("Couldn't find machine named " + rl + " for controller at " + getPos());
            }
        }

        super.readMachineNBT(compound);
    }

    @Override
    public void writeCustomNBT(NBTTagCompound compound) {
        super.writeCustomNBT(compound);

        if (this.activeRecipe != null) {
            compound.setTag("activeRecipe", this.activeRecipe.serialize());
        }
    }

}
