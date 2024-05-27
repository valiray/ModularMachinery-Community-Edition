package github.kasuminova.mmce.client.model;

import github.kasuminova.mmce.client.resource.GeoModelExternalLoader;
import github.kasuminova.mmce.common.concurrent.TaskExecutor;
import software.bernie.geckolib3.geo.render.built.GeoModel;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

public class ModelPool {

    // Reference thread pool size.
    public static final int POOL_LIMIT = TaskExecutor.CLIENT_THREAD_COUNT;

    private final MachineControllerModel original;

    private final Map<MachineControllerModel, GeoModel> renderInstMap = new ConcurrentHashMap<>();
    private final Queue<MachineControllerModel> renderInstPool = new ArrayBlockingQueue<>(POOL_LIMIT);

    private int modelCount = 0;

    public ModelPool(final MachineControllerModel original) {
        this.original = original;
        this.renderInstPool.offer(original);
    }

    public MachineControllerModel getOriginal() {
        return original;
    }

    public GeoModel getModel(final MachineControllerModel model) {
        if (model == original) {
            return GeoModelExternalLoader.INSTANCE.getModel(original.modelLocation);
        }
        return renderInstMap.get(model);
    }

    public synchronized MachineControllerModel borrowRenderInst() {
        if (renderInstPool.isEmpty() && modelCount < POOL_LIMIT) {
            modelCount++;
            GeoModel loaded = GeoModelExternalLoader.INSTANCE.load(original.modelLocation);
            if (loaded != null) {
                MachineControllerModel renderInst = original.createRenderInstance();
                renderInstMap.put(renderInst, loaded);
                return renderInst;
            }
        }
        return renderInstPool.poll();
    }

    public void returnRenderInst(MachineControllerModel model) {
        renderInstPool.offer(model);
    }

    public synchronized void reset() {
        renderInstPool.clear();
        renderInstMap.clear();
        modelCount = 0;
    }

}
