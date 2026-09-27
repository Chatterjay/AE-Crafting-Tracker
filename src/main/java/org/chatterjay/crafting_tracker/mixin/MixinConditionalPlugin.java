package org.chatterjay.crafting_tracker.mixin;

import java.io.IOException;
import java.io.InputStream;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Set;

public class MixinConditionalPlugin implements IMixinConfigPlugin {
    private static final String ECO_THREAD =
            "cn.dancingsnow.neoecoae.crafting.execution.worker.ECOCraftingThread";
    private static final String ECO_PATTERN_BUS =
            "cn.dancingsnow.neoecoae.blocks.entity.crafting.ECOCraftingPatternBusBlockEntity";
    private static final String ECO_EXACT_VIRTUAL_BATCH =
            "(Lcn/dancingsnow/neoecoae/crafting/execution/fastpath/ECOVerifiedFastPathRecipe;"
                    + "Ljava/math/BigInteger;Ljava/util/UUID;)Z";
    private static final String TRINITY_VIRTUAL_CPU =
            "com.fish_dan_.data_energistics.common.crafting.trinity.execution.cpu.TrinityDataCoreVirtualCpu";
    private static final String ECO_THREAD_INSTALL_WORK =
            "(Lcn/dancingsnow/neoecoae/crafting/execution/worker/ECOCraftingThreadWork;)V";
    private static final String NO_ARGUMENT_BOOLEAN = "()Z";
    private static final String BOOLEAN_ARGUMENT_VOID = "(Z)V";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith("ECOCraftingExactVirtualPatternBusHighlightMixin")) {
            return ECO_PATTERN_BUS.equals(targetClassName)
                    && hasMethod(ECO_PATTERN_BUS, "pushExactVirtualBatch", ECO_EXACT_VIRTUAL_BATCH);
        }
        if (ECO_THREAD.equals(targetClassName)) {
            return shouldApplyEcoThreadMixin(mixinClassName);
        }
        if (TRINITY_VIRTUAL_CPU.equals(targetClassName)) {
            return shouldApplyTrinityVirtualCpuMixin(mixinClassName);
        }
        if (targetClassName.startsWith("appeng.")
                || targetClassName.startsWith("com.glodblock.github.extendedae.")
                || targetClassName.startsWith("net.pedroksl.advanced_ae.")
                || targetClassName.startsWith("com.fish_dan_.data_energistics.")
                || targetClassName.startsWith("cn.dancingsnow.neoecoae.")) {
            String resource = targetClassName.replace('.', '/') + ".class";
            return getClass().getClassLoader().getResource(resource) != null;
        }
        return true;
    }

    /**
     * NeoECO installs every work kind through installWork in current builds; releases
     * without it start each work kind separately. Exactly one thread-side mixin is
     * applied so a worker is never attached twice for the same execution.
     */
    private boolean shouldApplyEcoThreadMixin(String mixinClassName) {
        boolean installsWork = hasMethod(ECO_THREAD, "installWork", ECO_THREAD_INSTALL_WORK);
        if (mixinClassName.endsWith("ECOCraftingThreadHighlightMixin")) {
            return installsWork;
        }
        if (mixinClassName.endsWith("ECOCraftingThreadLegacyHighlightMixin")) {
            return !installsWork && hasClass(ECO_THREAD);
        }
        return hasClass(ECO_THREAD);
    }

    private boolean shouldApplyTrinityVirtualCpuMixin(String mixinClassName) {
        if (mixinClassName.endsWith("TrinityDataCoreVirtualCpuSuspendMixin")) {
            return hasMethod(TRINITY_VIRTUAL_CPU, "isJobSuspended", NO_ARGUMENT_BOOLEAN)
                    && hasMethod(TRINITY_VIRTUAL_CPU, "setJobSuspended", BOOLEAN_ARGUMENT_VOID);
        }
        return hasClass(TRINITY_VIRTUAL_CPU);
    }

    private boolean hasClass(String className) {
        String resource = className.replace('.', '/') + ".class";
        return getClass().getClassLoader().getResource(resource) != null;
    }

    private boolean hasMethod(String className, String name, String descriptor) {
        String resource = className.replace('.', '/') + ".class";
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) return false;
            ClassNode classNode = new ClassNode();
            new ClassReader(stream).accept(classNode,
                    ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            for (MethodNode method : classNode.methods) {
                if (name.equals(method.name) && descriptor.equals(method.desc)) return true;
            }
        } catch (IOException ignored) {
            return false;
        }
        return false;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass, String mixinClassName,
                         IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass, String mixinClassName,
                          IMixinInfo mixinInfo) {
    }
}
