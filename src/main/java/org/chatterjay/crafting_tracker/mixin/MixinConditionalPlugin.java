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
    private static final String ECO_THREAD = "cn.dancingsnow.neoecoae.api.me.ECOCraftingThread";
    private static final String ECO_CPU_LOGIC = "cn.dancingsnow.neoecoae.api.me.ECOCraftingCPULogic";
    private static final String TRINITY_VIRTUAL_CPU =
            "com.fish_dan_.data_energistics.common.crafting.trinity.execution.cpu.TrinityDataCoreVirtualCpu";
    private static final String ECO_THREAD_MODERN_START_WORK =
            "(Ljava/util/List;Ljava/util/List;Ljava/util/List;Ljava/util/UUID;III)V";
    private static final String ECO_THREAD_LEGACY_START_WORK =
            "(Ljava/util/List;Ljava/util/List;Ljava/util/List;Ljava/util/UUID;I)V";
    private static final String ECO_CPU_MODERN_RECORD_PUSHED_PATTERN =
            "(Lcn/dancingsnow/neoecoae/api/me/ExecutingCraftingJob;"
                    + "Lcn/dancingsnow/neoecoae/impl/crafting/fastpath/ECOExtractedPatternExecution;J)V";
    private static final String ECO_CPU_LEGACY_RECORD_PUSHED_PATTERN =
            "(Lcn/dancingsnow/neoecoae/api/me/ExecutingCraftingJob;"
                    + "Lcn/dancingsnow/neoecoae/impl/crafting/fastpath/ECOExtractedPatternExecution;I)V";
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
        if (ECO_THREAD.equals(targetClassName)) {
            return shouldApplyEcoThreadMixin(mixinClassName);
        }
        if (ECO_CPU_LOGIC.equals(targetClassName)) {
            return shouldApplyEcoCpuLogicMixin(mixinClassName);
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

    private boolean shouldApplyEcoThreadMixin(String mixinClassName) {
        if (mixinClassName.endsWith("ECOCraftingThreadHighlightMixin")) {
            return hasMethod(ECO_THREAD, "startWork", ECO_THREAD_MODERN_START_WORK);
        }
        if (mixinClassName.endsWith("ECOCraftingThreadLegacyHighlightMixin")) {
            return hasMethod(ECO_THREAD, "startWork", ECO_THREAD_LEGACY_START_WORK);
        }
        return hasClass(ECO_THREAD);
    }

    private boolean shouldApplyEcoCpuLogicMixin(String mixinClassName) {
        if (mixinClassName.endsWith("ECOCraftingCPULogicCpuPriorityMixin")) {
            return hasMethod(ECO_CPU_LOGIC, "recordPushedPattern", ECO_CPU_MODERN_RECORD_PUSHED_PATTERN);
        }
        if (mixinClassName.endsWith("ECOCraftingCPULogicLegacyCpuPriorityMixin")) {
            return hasMethod(ECO_CPU_LOGIC, "recordPushedPattern", ECO_CPU_LEGACY_RECORD_PUSHED_PATTERN);
        }
        return hasClass(ECO_CPU_LOGIC);
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
